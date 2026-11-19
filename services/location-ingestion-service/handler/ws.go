package handler

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// CloseWebSockets closes all active WebSocket connections and waits for their goroutines.
func (h *LocationHandler) CloseWebSockets() {
	h.shuttingDown.Store(true)
	h.wsMu.Lock()
	conns := make([]*websocket.Conn, 0, len(h.wsConns))
	for conn := range h.wsConns {
		conns = append(conns, conn)
	}
	h.wsMu.Unlock()

	for _, conn := range conns {
		_ = conn.WriteControl(
			websocket.CloseMessage,
			websocket.FormatCloseMessage(websocket.CloseGoingAway, "server shutting down"),
			time.Now().Add(time.Second),
		)
		_ = conn.Close()
	}
	h.wsWG.Wait()
}

// ActiveWebSockets returns the count of currently open WebSocket connections.
func (h *LocationHandler) ActiveWebSockets() int {
	h.wsMu.Lock()
	defer h.wsMu.Unlock()
	return len(h.wsConns)
}

func (h *LocationHandler) registerWSConn(conn *websocket.Conn) bool {
	h.wsMu.Lock()
	defer h.wsMu.Unlock()
	if h.shuttingDown.Load() {
		return false
	}
	if h.wsConns == nil {
		h.wsConns = make(map[*websocket.Conn]struct{})
	}
	h.wsConns[conn] = struct{}{}
	return true
}

func (h *LocationHandler) unregisterWSConn(conn *websocket.Conn) {
	h.wsMu.Lock()
	delete(h.wsConns, conn)
	h.wsMu.Unlock()
}

// HandleWebSocket upgrades the connection and streams LocationInput GPS messages.
func (h *LocationHandler) HandleWebSocket(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		h.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if h.shuttingDown.Load() {
		h.writeError(w, http.StatusServiceUnavailable, "service shutting down")
		return
	}

	if h.rateLimiter != nil && !h.rateLimiter.Allow() {
		h.recordDropped("websocket", "rate_limit")
		w.Header().Set("Retry-After", "5")
		h.writeError(w, http.StatusTooManyRequests, "connection rate limit exceeded; apply exponential backoff")
		return
	}
	if h.rateLimiter != nil {
		defer h.rateLimiter.ReleaseConn()
	}

	upgrader := websocket.Upgrader{
		ReadBufferSize:  1024,
		WriteBufferSize: 1024,
		CheckOrigin:     func(_ *http.Request) bool { return true },
	}

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		h.logger.ErrorContext(r.Context(), "websocket upgrade failed", "error", err)
		return
	}
	if !h.registerWSConn(conn) {
		_ = conn.Close()
		return
	}

	h.wsWG.Add(1)
	defer h.wsWG.Done()
	defer h.unregisterWSConn(conn)
	defer conn.Close()

	h.handleWebSocketConn(r.Context(), conn)
}

func (h *LocationHandler) handleWebSocketConn(ctx context.Context, conn *websocket.Conn) {
	readMax := h.cfg.WebSocketReadMax
	if readMax <= 0 {
		readMax = defaultWSReadLimit
	}
	conn.SetReadLimit(readMax)
	_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	conn.SetPongHandler(func(string) error {
		_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		return nil
	})

	pingDone := make(chan struct{})
	var pingWG sync.WaitGroup
	pingWG.Add(1)
	go func() {
		defer pingWG.Done()
		ticker := time.NewTicker(25 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-pingDone:
				return
			case <-ticker.C:
				_ = conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
				if err := conn.WriteMessage(websocket.PingMessage, nil); err != nil {
					return
				}
			}
		}
	}()
	defer func() {
		close(pingDone)
		pingWG.Wait()
	}()

	for {
		select {
		case <-ctx.Done():
			return
		default:
		}
		if h.shuttingDown.Load() {
			return
		}

		messageType, payload, err := conn.ReadMessage()
		if err != nil {
			if websocket.IsCloseError(err, websocket.CloseNormalClosure, websocket.CloseGoingAway) {
				return
			}
			if !h.shuttingDown.Load() {
				h.logger.Warn("websocket read error", "error", err)
			}
			return
		}
		if messageType != websocket.TextMessage && messageType != websocket.BinaryMessage {
			continue
		}

		h.recordReceived("websocket")

		var input LocationInput
		decoder := json.NewDecoder(bytes.NewReader(payload))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&input); err != nil {
			h.recordInvalid("websocket", "decode")
			continue
		}
		if err := decoder.Decode(&struct{}{}); err != io.EOF {
			h.recordInvalid("websocket", "decode")
			continue
		}

		if err := h.ProcessInput(ctx, input, "websocket"); err != nil {
			if !errors.Is(err, ErrInvalidPayload) && !isOutOfOrderErr(err) {
				h.logger.Warn("websocket update rejected",
					"rider_id", input.RiderID, "error", err)
			}
		}
	}
}

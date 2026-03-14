package correlation

import (
	"context"
	"net/http"

	"github.com/google/uuid"
)

type contextKey string

const (
	Header     = "X-Correlation-ID"
	ContextKey contextKey = "correlationId"
)

func Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		cid := r.Header.Get(Header)
		if cid == "" {
			cid = uuid.NewString()
		}
		ctx := context.WithValue(r.Context(), ContextKey, cid)
		w.Header().Set(Header, cid)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func FromContext(ctx context.Context) string {
	if cid, ok := ctx.Value(ContextKey).(string); ok {
		return cid
	}
	return ""
}

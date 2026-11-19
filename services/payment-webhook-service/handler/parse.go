package handler

import (
	"encoding/json"
	"fmt"
	"time"
)

// ParseEvent converts a raw PSP payload into a canonical WebhookEvent.
func ParseEvent(psp PSPType, payload []byte) (WebhookEvent, error) {
	var raw map[string]interface{}
	if err := json.Unmarshal(payload, &raw); err != nil {
		return WebhookEvent{}, fmt.Errorf("handler: unmarshal payload: %w", err)
	}

	event := WebhookEvent{
		PSP:           psp,
		RawPayload:    payload,
		RawPSPPayload: json.RawMessage(payload),
		Version:       SchemaVersion,
		ReceivedAt:    time.Now().UTC(),
	}

	switch psp {
	case Stripe:
		event.ID, _ = jsonString(raw, "id")
		event.EventType, _ = jsonString(raw, "type")
		if data, ok := raw["data"].(map[string]interface{}); ok {
			if obj, ok := data["object"].(map[string]interface{}); ok {
				event.PaymentID, _ = jsonString(obj, "id")
				event.OrderID, _ = jsonString(obj, "metadata", "order_id")
				event.AmountCents = jsonInt64(obj, "amount")
				event.Currency, _ = jsonString(obj, "currency")
				event.Status, _ = jsonString(obj, "status")
			}
		}
	case Razorpay:
		event.EventType, _ = jsonString(raw, "event")
		if p, ok := raw["payload"].(map[string]interface{}); ok {
			if payment, ok := p["payment"].(map[string]interface{}); ok {
				if entity, ok := payment["entity"].(map[string]interface{}); ok {
					event.ID, _ = jsonString(entity, "id")
					event.PaymentID, _ = jsonString(entity, "id")
					event.OrderID, _ = jsonString(entity, "order_id")
					event.AmountCents = jsonInt64(entity, "amount")
					event.Currency, _ = jsonString(entity, "currency")
					event.Status, _ = jsonString(entity, "status")
				}
			}
		}
	case PhonePe:
		event.ID, _ = jsonString(raw, "transactionId")
		event.EventType, _ = jsonString(raw, "event")
		event.PaymentID, _ = jsonString(raw, "transactionId")
		event.OrderID, _ = jsonString(raw, "merchantOrderId")
		event.AmountCents = jsonInt64(raw, "amount")
		event.Currency = "INR"
		event.Status, _ = jsonString(raw, "state")
	default:
		return WebhookEvent{}, fmt.Errorf("handler: unsupported PSP %q", psp)
	}

	if event.ID == "" {
		return WebhookEvent{}, ErrMissingEventID
	}
	return event, nil
}

func (h *WebhookHandler) parseEvent(psp PSPType, payload []byte) (WebhookEvent, error) {
	return ParseEvent(psp, payload)
}

func jsonString(m map[string]interface{}, keys ...string) (string, bool) {
	current := m
	for i, key := range keys {
		val, ok := current[key]
		if !ok {
			return "", false
		}
		if i == len(keys)-1 {
			s, ok := val.(string)
			return s, ok
		}
		next, ok := val.(map[string]interface{})
		if !ok {
			return "", false
		}
		current = next
	}
	return "", false
}

func jsonInt64(m map[string]interface{}, key string) int64 {
	val, ok := m[key]
	if !ok {
		return 0
	}
	switch v := val.(type) {
	case float64:
		return int64(v)
	case int64:
		return v
	case json.Number:
		n, _ := v.Int64()
		return n
	default:
		return 0
	}
}

package kafka

import (
	"strings"

	kafkago "github.com/segmentio/kafka-go"
)

// HeaderCarrier adapts a slice of kafka.Header to propagation.TextMapCarrier
// for extracting and injecting trace context across Kafka messages.
type HeaderCarrier struct {
	headers *[]kafkago.Header
}

// NewHeaderCarrier wraps a Kafka header slice pointer as a TextMapCarrier.
func NewHeaderCarrier(headers *[]kafkago.Header) HeaderCarrier {
	return HeaderCarrier{headers: headers}
}

func (c HeaderCarrier) Get(key string) string {
	if c.headers == nil {
		return ""
	}
	for _, h := range *c.headers {
		if strings.EqualFold(h.Key, key) {
			return string(h.Value)
		}
	}
	return ""
}

func (c HeaderCarrier) Set(key, value string) {
	if c.headers == nil {
		return
	}
	for i := range *c.headers {
		if strings.EqualFold((*c.headers)[i].Key, key) {
			(*c.headers)[i].Value = []byte(value)
			return
		}
	}
	*c.headers = append(*c.headers, kafkago.Header{Key: key, Value: []byte(value)})
}

func (c HeaderCarrier) Keys() []string {
	if c.headers == nil {
		return nil
	}
	keys := make([]string, 0, len(*c.headers))
	for _, h := range *c.headers {
		keys = append(keys, h.Key)
	}
	return keys
}

package processor

import (
	"container/list"
	"time"
)

func (p *RiderProcessor) recordLocationTimestampLocked(riderID string, eventTime time.Time) bool {
	if p.lastLocationAt == nil {
		p.lastLocationAt = make(map[string]*riderLocationEntry)
	}
	if p.lastLocationLRU == nil {
		p.lastLocationLRU = list.New()
	}
	ttl := p.locationStateTTL
	if ttl <= 0 {
		ttl = defaultLocationStateTTL
	}
	maxStates := p.maxLocationStates
	if maxStates <= 0 {
		maxStates = defaultLocationMaxStates
	}
	now := time.Now().UTC()
	if p.nowFn != nil {
		now = p.nowFn()
	}

	for back := p.lastLocationLRU.Back(); back != nil; {
		prev := back.Prev()
		id := back.Value.(string)
		entry := p.lastLocationAt[id]
		if entry == nil || now.Sub(entry.seenAt) > ttl {
			p.lastLocationLRU.Remove(back)
			delete(p.lastLocationAt, id)
			back = prev
			continue
		}
		break
	}

	if entry, ok := p.lastLocationAt[riderID]; ok {
		if eventTime.Before(entry.eventTime) {
			return false
		}
		entry.eventTime = eventTime
		entry.seenAt = now
		if entry.elem != nil {
			p.lastLocationLRU.MoveToFront(entry.elem)
		} else {
			entry.elem = p.lastLocationLRU.PushFront(riderID)
		}
		return true
	}

	for len(p.lastLocationAt) >= maxStates {
		back := p.lastLocationLRU.Back()
		if back == nil {
			break
		}
		id := back.Value.(string)
		p.lastLocationLRU.Remove(back)
		delete(p.lastLocationAt, id)
	}

	entry := &riderLocationEntry{
		eventTime: eventTime,
		seenAt:    now,
	}
	entry.elem = p.lastLocationLRU.PushFront(riderID)
	p.lastLocationAt[riderID] = entry
	return true
}

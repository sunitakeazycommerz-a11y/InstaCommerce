package processor

import (
	"context"
	"sync"

	"github.com/segmentio/kafka-go"
)

type topicPartition struct {
	topic     string
	partition int
}

type offsetStatus uint8

const (
	offsetPending offsetStatus = iota
	offsetCompleted
	offsetFailed
)

type trackedOffset struct {
	msg       kafka.Message
	status    offsetStatus
	discarded bool
}

type partitionTracker struct {
	mu            sync.Mutex
	queue         []*trackedOffset
	failedSeen    bool
	failedOffset  int64
	readyToCommit *kafka.Message
	committing    bool
}

type offsetTracker struct {
	mu         sync.Mutex
	partitions map[topicPartition]*partitionTracker
}

func newOffsetTracker() *offsetTracker {
	return &offsetTracker{
		partitions: make(map[topicPartition]*partitionTracker),
	}
}

func (ot *offsetTracker) partition(msg kafka.Message) *partitionTracker {
	key := topicPartition{topic: msg.Topic, partition: msg.Partition}
	ot.mu.Lock()
	pt, ok := ot.partitions[key]
	if !ok {
		pt = &partitionTracker{}
		ot.partitions[key] = pt
	}
	ot.mu.Unlock()
	return pt
}

func (ot *offsetTracker) enqueue(msg kafka.Message) *trackedOffset {
	pt := ot.partition(msg)
	pt.mu.Lock()
	defer pt.mu.Unlock()
	if pt.failedSeen {
		if msg.Offset <= pt.failedOffset {
			pt.resetFailedLocked()
		} else {
			return nil
		}
	}
	entry := &trackedOffset{msg: msg, status: offsetPending}
	pt.queue = append(pt.queue, entry)
	return entry
}

func (pt *partitionTracker) resetFailedLocked() {
	pt.failedSeen = false
	pt.failedOffset = 0
	for i := range pt.queue {
		if pt.queue[i] != nil {
			pt.queue[i].discarded = true
			pt.queue[i] = nil
		}
	}
	pt.queue = pt.queue[:0]
}

func (ot *offsetTracker) cancel(msg kafka.Message, entry *trackedOffset) {
	if entry == nil {
		return
	}
	pt := ot.partition(msg)
	pt.mu.Lock()
	defer pt.mu.Unlock()
	if entry.discarded {
		return
	}
	for i := len(pt.queue) - 1; i >= 0; i-- {
		if pt.queue[i] == entry {
			copy(pt.queue[i:], pt.queue[i+1:])
			pt.queue[len(pt.queue)-1] = nil
			pt.queue = pt.queue[:len(pt.queue)-1]
			entry.discarded = true
			return
		}
	}
}

func (ot *offsetTracker) resolve(
	ctx context.Context,
	msg kafka.Message,
	entry *trackedOffset,
	succeeded bool,
	commitFn func(context.Context, kafka.Message) error,
) error {
	if entry == nil {
		return nil
	}
	pt := ot.partition(msg)
	pt.mu.Lock()
	if entry.discarded {
		pt.mu.Unlock()
		return nil
	}
	if !succeeded {
		entry.status = offsetFailed
		pt.failedSeen = true
		pt.failedOffset = msg.Offset
		for i, item := range pt.queue {
			if item == entry {
				for j := i + 1; j < len(pt.queue); j++ {
					pt.queue[j].discarded = true
					pt.queue[j] = nil
				}
				pt.queue = pt.queue[:i+1]
				break
			}
		}
		return pt.commitReady(ctx, commitFn)
	}
	entry.status = offsetCompleted
	return pt.commitReady(ctx, commitFn)
}

func (ot *offsetTracker) ackFailed(
	ctx context.Context,
	msg kafka.Message,
	commitFn func(context.Context, kafka.Message) error,
) error {
	pt := ot.partition(msg)
	pt.mu.Lock()
	for _, item := range pt.queue {
		if item != nil && item.msg.Offset == msg.Offset && item.status == offsetFailed {
			item.status = offsetCompleted
			break
		}
	}
	hasFailed := false
	for _, item := range pt.queue {
		if item != nil && item.status == offsetFailed {
			hasFailed = true
			pt.failedOffset = item.msg.Offset
			break
		}
	}
	pt.failedSeen = hasFailed
	if !hasFailed {
		pt.failedOffset = 0
	}
	return pt.commitReady(ctx, commitFn)
}

func (ot *offsetTracker) skip(
	ctx context.Context,
	msg kafka.Message,
	commitFn func(context.Context, kafka.Message) error,
) error {
	pt := ot.partition(msg)
	pt.mu.Lock()
	if pt.failedSeen {
		if msg.Offset <= pt.failedOffset {
			pt.resetFailedLocked()
		} else {
			pt.mu.Unlock()
			return nil
		}
	}
	entry := &trackedOffset{msg: msg, status: offsetCompleted}
	pt.queue = append(pt.queue, entry)
	return pt.commitReady(ctx, commitFn)
}

func (pt *partitionTracker) commitReady(
	ctx context.Context,
	commitFn func(context.Context, kafka.Message) error,
) error {
	if !pt.advanceAndCheckFlush(commitFn != nil) {
		return nil
	}
	return pt.flushCommits(ctx, commitFn)
}

func (pt *partitionTracker) advanceAndCheckFlush(hasCommitter bool) bool {
	defer pt.mu.Unlock()
	pt.advanceLocked()
	if !hasCommitter {
		pt.readyToCommit = nil
		return false
	}
	if pt.readyToCommit == nil || pt.committing {
		return false
	}
	pt.committing = true
	return true
}

func (pt *partitionTracker) advanceLocked() {
	popped := 0
	for popped < len(pt.queue) && pt.queue[popped].status == offsetCompleted {
		msg := pt.queue[popped].msg
		pt.readyToCommit = &msg
		pt.queue[popped] = nil
		popped++
	}
	if popped == 0 {
		return
	}
	remaining := len(pt.queue) - popped
	if remaining == 0 {
		if cap(pt.queue) > 1024 {
			pt.queue = nil
		} else {
			pt.queue = pt.queue[:0]
		}
		return
	}
	copy(pt.queue, pt.queue[popped:])
	clear(pt.queue[remaining:])
	pt.queue = pt.queue[:remaining]
}

func (pt *partitionTracker) takeReadyCommit() (kafka.Message, bool) {
	pt.mu.Lock()
	defer pt.mu.Unlock()
	if pt.readyToCommit == nil {
		pt.committing = false
		return kafka.Message{}, false
	}
	msg := *pt.readyToCommit
	pt.readyToCommit = nil
	return msg, true
}

func (pt *partitionTracker) restoreReadyCommit(msg kafka.Message) {
	pt.mu.Lock()
	defer pt.mu.Unlock()
	if pt.readyToCommit == nil || pt.readyToCommit.Offset < msg.Offset {
		cp := msg
		pt.readyToCommit = &cp
	}
	pt.committing = false
}

func (pt *partitionTracker) flushCommits(ctx context.Context, commitFn func(context.Context, kafka.Message) error) error {
	completed := false
	defer func() {
		if !completed {
			pt.mu.Lock()
			pt.committing = false
			pt.mu.Unlock()
		}
	}()

	var lastErr error
	for {
		msg, ok := pt.takeReadyCommit()
		if !ok {
			completed = true
			return lastErr
		}
		if err := commitFn(ctx, msg); err != nil {
			pt.restoreReadyCommit(msg)
			completed = true
			return err
		}
	}
}

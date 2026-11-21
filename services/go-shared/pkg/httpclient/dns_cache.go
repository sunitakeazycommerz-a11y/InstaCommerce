package httpclient

import (
	"context"
	"net"
	"slices"
	"sync"
	"time"
)

const maxDNSCacheEntries = 1024

// DNSCache caches DNS lookups with a configurable TTL to prevent indefinite DNS
// caching while avoiding DNS resolution overhead on every HTTP request.
type DNSCache struct {
	resolver *net.Resolver
	ttl      time.Duration
	mu       sync.RWMutex
	entries  map[string]dnsEntry
}

type dnsEntry struct {
	ips       []string
	expiresAt time.Time
}

// NewDNSCache returns a new DNSCache with the specified TTL.
func NewDNSCache(ttl time.Duration) *DNSCache {
	return &DNSCache{
		resolver: &net.Resolver{PreferGo: true},
		ttl:      ttl,
		entries:  make(map[string]dnsEntry),
	}
}

// LookupHost resolves host to IP addresses, caching successful results for ttl.
func (c *DNSCache) LookupHost(ctx context.Context, host string) ([]string, error) {
	now := time.Now()
	c.mu.RLock()
	entry, ok := c.entries[host]
	c.mu.RUnlock()

	if ok && now.Before(entry.expiresAt) {
		return slices.Clone(entry.ips), nil
	}

	ips, err := c.resolver.LookupHost(ctx, host)
	if err != nil {
		if ok && len(entry.ips) > 0 {
			return slices.Clone(entry.ips), nil
		}
		return nil, err
	}

	if c.ttl > 0 {
		now = time.Now()
		c.mu.Lock()
		if _, exists := c.entries[host]; !exists && len(c.entries) >= maxDNSCacheEntries {
			c.evictLocked(now)
		}
		c.entries[host] = dnsEntry{
			ips:       slices.Clone(ips),
			expiresAt: now.Add(c.ttl),
		}
		c.mu.Unlock()
	}

	return ips, nil
}

func (c *DNSCache) evictLocked(now time.Time) {
	for k, v := range c.entries {
		if !now.Before(v.expiresAt) {
			delete(c.entries, k)
		}
	}
	if len(c.entries) < maxDNSCacheEntries {
		return
	}
	c.evictEarliestExpiringLocked()
}

func (c *DNSCache) evictEarliestExpiringLocked() {
	var oldestKey string
	var oldestExp time.Time
	first := true
	for k, v := range c.entries {
		if first || v.expiresAt.Before(oldestExp) {
			oldestKey = k
			oldestExp = v.expiresAt
			first = false
		}
	}
	if !first {
		delete(c.entries, oldestKey)
	}
}

// DialContext returns a dial function that resolves hostnames through the DNS cache.
func (c *DNSCache) DialContext(dialer *net.Dialer) func(ctx context.Context, network, addr string) (net.Conn, error) {
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(addr)
		if err != nil {
			return dialer.DialContext(ctx, network, addr)
		}
		if ip := net.ParseIP(host); ip != nil {
			return dialer.DialContext(ctx, network, addr)
		}

		ips, err := c.LookupHost(ctx, host)
		if err != nil || len(ips) == 0 {
			return dialer.DialContext(ctx, network, addr)
		}

		var lastErr error
		for _, ip := range ips {
			target := net.JoinHostPort(ip, port)
			conn, err := dialer.DialContext(ctx, network, target)
			if err == nil {
				return conn, nil
			}
			lastErr = err
		}
		return nil, lastErr
	}
}

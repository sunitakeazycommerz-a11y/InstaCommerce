package main

import (
	"context"
	"net"
	"time"

	"github.com/IBM/sarama"
	"github.com/instacommerce/go-shared/pkg/health"
)

type contextProxyDialer struct {
	ctx    context.Context
	dialer interface {
		Dial(network, addr string) (net.Conn, error)
	}
}

func (d *contextProxyDialer) Dial(network, addr string) (net.Conn, error) {
	if err := d.ctx.Err(); err != nil {
		return nil, err
	}

	var conn net.Conn
	var err error
	if ctxDialer, ok := d.dialer.(interface {
		DialContext(context.Context, string, string) (net.Conn, error)
	}); ok {
		conn, err = ctxDialer.DialContext(d.ctx, network, addr)
	} else {
		conn, err = d.dialer.Dial(network, addr)
	}
	if err != nil {
		return nil, err
	}

	stop := context.AfterFunc(d.ctx, func() {
		_ = conn.SetDeadline(time.Now())
	})
	return &deadlineConn{Conn: conn, stop: stop}, nil
}

type deadlineConn struct {
	net.Conn
	stop func() bool
}

func (c *deadlineConn) Close() error {
	if c.stop != nil {
		c.stop()
	}
	return c.Conn.Close()
}

func (s *relayService) probeContext(ctx context.Context) (context.Context, context.CancelFunc, time.Duration, error) {
	readyTimeout := s.cfg.ReadyTimeout
	if readyTimeout <= 0 {
		readyTimeout = defaultReadyTimeout
	}
	return health.BrokerAttemptContext(ctx, 1, readyTimeout)
}

func (s *relayService) healthKafkaConfig(ctx context.Context, timeout time.Duration) *sarama.Config {
	fresh := sarama.NewConfig()
	var cfg sarama.Config
	if s.kafkaConfig != nil {
		cfg = *s.kafkaConfig
		cfg.MetricRegistry = fresh.MetricRegistry
	} else {
		cfg = *fresh
	}

	socketTimeout := timeout + 50*time.Millisecond

	baseDialer := cfg.Net.Proxy.Dialer
	if !cfg.Net.Proxy.Enable || baseDialer == nil {
		baseDialer = &net.Dialer{
			Timeout:   socketTimeout,
			KeepAlive: cfg.Net.KeepAlive,
			LocalAddr: cfg.Net.LocalAddr,
		}
	}

	cfg.Net.DialTimeout = socketTimeout
	cfg.Net.ReadTimeout = socketTimeout
	cfg.Net.WriteTimeout = socketTimeout
	cfg.Metadata.Timeout = socketTimeout
	cfg.Metadata.Retry.Max = 0
	cfg.Metadata.RefreshFrequency = 0
	cfg.ApiVersionsRequest = false
	cfg.Net.Proxy.Enable = true
	cfg.Net.Proxy.Dialer = &contextProxyDialer{ctx: ctx, dialer: baseDialer}
	return &cfg
}

func probeError(ctx context.Context, err error) error {
	if ctxErr := ctx.Err(); ctxErr != nil {
		return ctxErr
	}
	if deadline, ok := ctx.Deadline(); ok && !time.Now().Before(deadline) {
		return context.DeadlineExceeded
	}
	return err
}

func (s *relayService) checkKafka(ctx context.Context) error {
	probeCtx, cancel, timeout, err := s.probeContext(ctx)
	if err != nil {
		return err
	}
	defer cancel()

	client, err := sarama.NewClient(s.cfg.KafkaBrokers, s.healthKafkaConfig(probeCtx, timeout))
	if err != nil {
		return probeError(probeCtx, err)
	}
	defer client.Close()

	if err := probeCtx.Err(); err != nil {
		return err
	}

	controller, err := client.Controller()
	if err != nil {
		return probeError(probeCtx, err)
	}

	connected, err := controller.Connected()
	if err != nil {
		return probeError(probeCtx, err)
	}
	if !connected {
		return probeError(probeCtx, sarama.ErrNotConnected)
	}
	return nil
}

func newKafkaProducer(cfg Config) (sarama.SyncProducer, *sarama.Config, error) {
	timeout := cfg.ReadyTimeout
	if timeout < 5*time.Second {
		timeout = 5 * time.Second
	}

	kafkaConfig := sarama.NewConfig()
	kafkaConfig.ClientID = cfg.KafkaClientID
	kafkaConfig.Version = sarama.V2_5_0_0
	kafkaConfig.Net.MaxOpenRequests = 1
	kafkaConfig.Net.DialTimeout = timeout
	kafkaConfig.Net.ReadTimeout = timeout
	kafkaConfig.Net.WriteTimeout = timeout
	kafkaConfig.Producer.RequiredAcks = sarama.WaitForAll
	kafkaConfig.Producer.Retry.Max = 10
	kafkaConfig.Producer.Return.Successes = true
	kafkaConfig.Producer.Idempotent = true

	producer, err := sarama.NewSyncProducer(cfg.KafkaBrokers, kafkaConfig)
	if err != nil {
		return nil, nil, err
	}
	return producer, kafkaConfig, nil
}

package com.instacommerce.identity.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class AuthMetrics {
    private final Counter loginSuccessCounter;
    private final Counter loginFailureCounter;
    private final Counter registerCounter;
    private final Counter refreshCounter;
    private final Counter revokeCounter;
    private final Timer loginTimer;

    public AuthMetrics(MeterRegistry meterRegistry) {
        this.loginSuccessCounter = Counter.builder("auth.login.total")
            .tag("result", "success")
            .register(meterRegistry);
        this.loginFailureCounter = Counter.builder("auth.login.total")
            .tag("result", "failure")
            .register(meterRegistry);
        this.registerCounter = Counter.builder("auth.register.total")
            .register(meterRegistry);
        this.refreshCounter = Counter.builder("auth.refresh.total")
            .register(meterRegistry);
        this.revokeCounter = Counter.builder("auth.revoke.total")
            .register(meterRegistry);
        this.loginTimer = Timer.builder("auth.login.duration")
            .register(meterRegistry);
    }

    public void incrementLoginSuccess() {
        loginSuccessCounter.increment();
    }

    public void incrementLoginFailure() {
        loginFailureCounter.increment();
    }

    public void incrementRegister() {
        registerCounter.increment();
    }

    public void incrementRefresh() {
        refreshCounter.increment();
    }

    public void incrementRevoke() {
        revokeCounter.increment();
    }

    public Timer getLoginTimer() {
        return loginTimer;
    }
}

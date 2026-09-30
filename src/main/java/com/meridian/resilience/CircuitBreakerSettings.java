package com.meridian.resilience;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Simulated breaker thresholds. They do not contact Adyen or Worldpay. */
@Component
public class CircuitBreakerSettings {
    private final double errorRateThreshold;
    private final Duration window;
    private final int minSamples;
    private final Duration openCooldown;

    public CircuitBreakerSettings(
        @Value("${meridian.circuit-breaker.error-rate-threshold:0.05}") double errorRateThreshold,
        @Value("${meridian.circuit-breaker.window-seconds:60}") long windowSeconds,
        @Value("${meridian.circuit-breaker.min-samples:20}") int minSamples,
        @Value("${meridian.circuit-breaker.open-cooldown-seconds:30}") long openCooldownSeconds) {
        if (errorRateThreshold <= 0 || errorRateThreshold >= 1) {
            throw new IllegalArgumentException("Circuit breaker error rate must be between 0 and 1");
        }
        if (windowSeconds < 1 || minSamples < 1 || openCooldownSeconds < 1) {
            throw new IllegalArgumentException("Circuit breaker window, sample count and cooldown must be positive");
        }
        this.errorRateThreshold = errorRateThreshold;
        this.window = Duration.ofSeconds(windowSeconds);
        this.minSamples = minSamples;
        this.openCooldown = Duration.ofSeconds(openCooldownSeconds);
    }

    public double errorRateThreshold() { return errorRateThreshold; }
    public Duration window() { return window; }
    public int minSamples() { return minSamples; }
    public Duration openCooldown() { return openCooldown; }
}

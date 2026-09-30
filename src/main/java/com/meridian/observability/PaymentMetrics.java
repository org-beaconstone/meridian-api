package com.meridian.observability;

import com.meridian.payment.PaymentProvider;
import com.meridian.resilience.CorridorCircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Prometheus series for the rehearsal ledger. Labels stay low-cardinality: provider and corridor only. */
@Component
public class PaymentMetrics {
    static final long SCA_WINDOW_MS = 300_000;
    static final int SCA_MIN_SAMPLES = 20;
    private static final List<String> PROVIDERS = List.of("adyen", "worldpay");
    private static final List<String> CORRIDORS = List.of("UK", "US", "EU");
    private static final List<String> OUTCOMES = List.of("success", "declined", "unavailable", "pending", "ambiguous", "degraded");

    private record Key(String provider, String corridor) {}
    private record Sample(long epochMs, boolean success) {}

    private static final class Window {
        private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    }

    private final MeterRegistry registry;
    private final CorridorCircuitBreaker breakers;
    private final ConcurrentHashMap<Key, Window> scaWindows = new ConcurrentHashMap<>();

    public PaymentMetrics(MeterRegistry registry, CorridorCircuitBreaker breakers) {
        this.registry = registry;
        this.breakers = breakers;
        for (String provider : PROVIDERS) {
            for (String corridor : CORRIDORS) {
                Gauge.builder("circuit.breaker.state", breakers, breaker -> breaker.stateCode(provider, corridor))
                    .tag("provider", provider)
                    .tag("corridor", corridor)
                    .description("Simulated circuit state: 0 closed, 1 open, 2 half-open")
                    .register(registry);
                Gauge.builder("sca.step.up.success.rate", this, metrics -> metrics.scaRate(provider, corridor))
                    .tag("provider", provider)
                    .tag("corridor", corridor)
                    .description("Rolling 5-minute simulated SCA step-up success rate")
                    .register(registry);
                for (String outcome : OUTCOMES) submissions(provider, corridor, outcome);
                timeouts(provider, corridor);
                attempts(provider, corridor);
                successes(provider, corridor);
                authorizationTimer(provider, corridor);
            }
        }
    }

    public void submission(String provider, String corridor, String outcome) {
        submissions(provider, corridor, outcome).increment();
    }

    public void observeAuthorization(String provider, String corridor, PaymentProvider.Outcome outcome, boolean ambiguous, long nanos) {
        String label = ambiguous ? "ambiguous" : outcome.name().toLowerCase(Locale.ROOT);
        submission(provider, corridor, label);
        authorizationTimer(provider, corridor).record(nanos, TimeUnit.NANOSECONDS);
        if (ambiguous || outcome == PaymentProvider.Outcome.UNAVAILABLE) timeouts(provider, corridor).increment();
        boolean stepUpSucceeded = !ambiguous && outcome == PaymentProvider.Outcome.SUCCESS;
        recordSca(provider, corridor, stepUpSucceeded);
        attempts(provider, corridor).increment();
        if (stepUpSucceeded) successes(provider, corridor).increment();
    }

    public synchronized void reset() {
        scaWindows.clear();
    }

    private double scaRate(String provider, String corridor) {
        Window window = scaWindows.get(new Key(provider, corridor));
        if (window == null) return 1.0;
        synchronized (window) {
            evict(window, System.currentTimeMillis());
            if (window.samples.size() < SCA_MIN_SAMPLES) return 1.0;
            long succeeded = 0;
            for (Sample sample : window.samples) if (sample.success()) succeeded++;
            return succeeded / (double) window.samples.size();
        }
    }

    private void recordSca(String provider, String corridor, boolean success) {
        Window window = scaWindows.computeIfAbsent(new Key(provider, corridor), key -> new Window());
        synchronized (window) {
            long now = System.currentTimeMillis();
            evict(window, now);
            window.samples.addLast(new Sample(now, success));
        }
    }

    private void evict(Window window, long now) {
        long cutoff = now - SCA_WINDOW_MS;
        while (!window.samples.isEmpty() && window.samples.peekFirst().epochMs() <= cutoff) {
            window.samples.removeFirst();
        }
    }

    private Counter submissions(String provider, String corridor, String outcome) {
        return Counter.builder("payment.submissions")
            .tag("provider", provider)
            .tag("corridor", corridor)
            .tag("outcome", outcome)
            .description("Simulated payment submissions")
            .register(registry);
    }

    private Counter timeouts(String provider, String corridor) {
        return Counter.builder("payment.provider.timeouts")
            .tag("provider", provider)
            .tag("corridor", corridor)
            .description("Simulated provider timeouts. The unavailable scenario counts as a timeout.")
            .register(registry);
    }

    private Counter attempts(String provider, String corridor) {
        return Counter.builder("sca.step.up.attempts")
            .tag("provider", provider)
            .tag("corridor", corridor)
            .description("Simulated SCA step-up attempts")
            .register(registry);
    }

    private Counter successes(String provider, String corridor) {
        return Counter.builder("sca.step.up.success")
            .tag("provider", provider)
            .tag("corridor", corridor)
            .description("Simulated SCA step-up successes")
            .register(registry);
    }

    private Timer authorizationTimer(String provider, String corridor) {
        return Timer.builder("payment.authorization")
            .tag("provider", provider)
            .tag("corridor", corridor)
            .description("Simulated authorization latency tracked against the 800ms SEPA Instant p95 SLO")
            .publishPercentileHistogram()
            .serviceLevelObjectives(Duration.ofMillis(800))
            .register(registry);
    }
}

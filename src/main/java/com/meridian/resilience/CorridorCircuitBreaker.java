package com.meridian.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-provider, per-corridor breaker for the simulated Adyen and Worldpay ports.
 * An open lane rejects new calls. It does not reroute them to the other provider:
 * an ambiguous authorization is never retried elsewhere, and a confirmed miss is
 * left for the client to resubmit on a healthy corridor with its own idempotency key.
 */
@Component
public class CorridorCircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private static final Logger log = LoggerFactory.getLogger(CorridorCircuitBreaker.class);
    private static final List<String> PROVIDERS = List.of("adyen", "worldpay");
    private static final List<String> CORRIDORS = List.of("UK", "US", "EU");
    private static final long MAX_COOLDOWN_MS = Duration.ofMinutes(5).toMillis();

    private record Key(String provider, String corridor) {}
    private record Sample(long epochMs, boolean error) {}

    private static final class Lane {
        private final ArrayDeque<Sample> samples = new ArrayDeque<>();
        private State state = State.CLOSED;
        private long openUntilMs;
        private int reopenings;
        private boolean probeInFlight;
    }

    private final CircuitBreakerSettings settings;
    private final Clock clock;
    private final ConcurrentHashMap<Key, Lane> lanes = new ConcurrentHashMap<>();

    public CorridorCircuitBreaker(CircuitBreakerSettings settings) {
        this(settings, Clock.systemUTC());
    }

    public CorridorCircuitBreaker(CircuitBreakerSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public synchronized boolean allow(String provider, String corridor) {
        Lane lane = lane(provider, corridor);
        long now = clock.millis();
        return switch (lane.state) {
            case CLOSED -> true;
            case OPEN -> {
                if (now < lane.openUntilMs) yield false;
                lane.state = State.HALF_OPEN;
                lane.probeInFlight = true;
                yield true;
            }
            case HALF_OPEN -> {
                if (lane.probeInFlight) yield false;
                lane.probeInFlight = true;
                yield true;
            }
        };
    }

    /** Records one provider attempt. Declines are not errors. Call only after {@link #allow} returned true. */
    public synchronized void record(String provider, String corridor, boolean error) {
        Lane lane = lane(provider, corridor);
        long now = clock.millis();
        lane.probeInFlight = false;
        evict(lane, now);
        lane.samples.addLast(new Sample(now, error));
        if (lane.state == State.HALF_OPEN) {
            if (error) open(provider, corridor, lane, now, true);
            else close(lane);
            return;
        }
        if (lane.samples.size() >= settings.minSamples() && errorRate(lane) > settings.errorRateThreshold()) {
            open(provider, corridor, lane, now, false);
        }
    }

    public synchronized State state(String provider, String corridor) {
        return lane(provider, corridor).state;
    }

    public int stateCode(String provider, String corridor) {
        return switch (state(provider, corridor)) {
            case CLOSED -> 0;
            case OPEN -> 1;
            case HALF_OPEN -> 2;
        };
    }

    /** False when either European lane is open, so clients hide EUR options and use the UK corridor. */
    public boolean eurOptionsEnabled() {
        return state("adyen", "EU") != State.OPEN && state("worldpay", "EU") != State.OPEN;
    }

    public synchronized Map<String, Object> snapshot() {
        var body = new LinkedHashMap<String, Object>();
        body.put("simulation", true);
        body.put("ledgerCurrency", "GBP");
        body.put("amounts", "integer pence");
        body.put("eurOptionsEnabled", eurOptionsEnabled());
        body.put("fallbackCorridor", "UK");
        body.put("errorRateThreshold", settings.errorRateThreshold());
        body.put("windowSeconds", settings.window().toSeconds());
        var corridors = new ArrayList<Map<String, Object>>();
        for (String corridor : CORRIDORS) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", corridor);
            var providers = new ArrayList<Map<String, Object>>();
            for (String provider : PROVIDERS) {
                State laneState = state(provider, corridor);
                var lane = new LinkedHashMap<String, Object>();
                lane.put("id", provider);
                lane.put("state", laneState.name());
                lane.put("accepting", laneState != State.OPEN);
                providers.add(lane);
            }
            row.put("providers", providers);
            corridors.add(row);
        }
        body.put("corridors", corridors);
        return body;
    }

    public synchronized void reset() {
        lanes.clear();
    }

    private void open(String provider, String corridor, Lane lane, long now, boolean reopen) {
        lane.reopenings = reopen ? lane.reopenings + 1 : 1;
        long factor = 1L << Math.min(lane.reopenings - 1, 4);
        long cooldown = Math.min(settings.openCooldown().toMillis() * factor, MAX_COOLDOWN_MS);
        lane.openUntilMs = now + cooldown;
        lane.state = State.OPEN;
        lane.probeInFlight = false;
        log.warn("Simulated circuit open provider={} corridor={} cooldownMs={} threshold={}",
            provider, corridor, cooldown, settings.errorRateThreshold());
    }

    private void close(Lane lane) {
        lane.state = State.CLOSED;
        lane.samples.clear();
        lane.reopenings = 0;
        lane.probeInFlight = false;
        lane.openUntilMs = 0;
    }

    private void evict(Lane lane, long now) {
        long cutoff = now - settings.window().toMillis();
        while (!lane.samples.isEmpty() && lane.samples.peekFirst().epochMs() <= cutoff) {
            lane.samples.removeFirst();
        }
    }

    private double errorRate(Lane lane) {
        long errors = 0;
        for (Sample sample : lane.samples) if (sample.error()) errors++;
        return errors / (double) lane.samples.size();
    }

    private Lane lane(String provider, String corridor) {
        if (!PROVIDERS.contains(provider) || !CORRIDORS.contains(corridor)) {
            throw new IllegalArgumentException("Breaker lanes exist only for Adyen and Worldpay on UK, US and EU");
        }
        return lanes.computeIfAbsent(new Key(provider, corridor), key -> new Lane());
    }
}

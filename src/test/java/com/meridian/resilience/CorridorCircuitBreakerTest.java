package com.meridian.resilience;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorridorCircuitBreakerTest {
    @Test
    void staysClosedAtExactlyFivePercentAndBelowMinimumSamples() {
        CorridorCircuitBreaker breaker = breaker(clock());
        for (int i = 0; i < 19; i++) breaker.record("adyen", "EU", true);
        assertEquals(CorridorCircuitBreaker.State.CLOSED, breaker.state("adyen", "EU"));

        breaker.reset();
        breaker.record("adyen", "EU", true);
        for (int i = 0; i < 19; i++) breaker.record("adyen", "EU", false);
        assertEquals(CorridorCircuitBreaker.State.CLOSED, breaker.state("adyen", "EU"));
        assertTrue(breaker.eurOptionsEnabled());
    }

    @Test
    void opensWhenEuropeanErrorRateExceedsFivePercentInTheWindow() {
        CorridorCircuitBreaker breaker = breaker(clock());
        breaker.record("adyen", "EU", true);
        breaker.record("adyen", "EU", true);
        for (int i = 0; i < 18; i++) breaker.record("adyen", "EU", false);
        assertEquals(CorridorCircuitBreaker.State.OPEN, breaker.state("adyen", "EU"));
        assertEquals(1, breaker.stateCode("adyen", "EU"));
        assertFalse(breaker.allow("adyen", "EU"));
        assertFalse(breaker.eurOptionsEnabled());
        assertEquals(CorridorCircuitBreaker.State.CLOSED, breaker.state("adyen", "UK"));
        assertEquals(CorridorCircuitBreaker.State.CLOSED, breaker.state("worldpay", "EU"));
        assertTrue(breaker.allow("adyen", "UK"));
        assertTrue(breaker.allow("worldpay", "EU"));
    }

    @Test
    void declinesDoNotTripTheBreaker() {
        CorridorCircuitBreaker breaker = breaker(clock());
        for (int i = 0; i < 40; i++) breaker.record("worldpay", "US", false);
        assertEquals(CorridorCircuitBreaker.State.CLOSED, breaker.state("worldpay", "US"));
    }

    @Test
    void agedErrorsLeaveTheOneMinuteWindow() {
        MutableClock clock = clock();
        CorridorCircuitBreaker breaker = breaker(clock);
        for (int i = 0; i < 19; i++) breaker.record("adyen", "US", true);
        clock.advance(Duration.ofSeconds(61));
        breaker.record("adyen", "US", true);
        assertEquals(CorridorCircuitBreaker.State.CLOSED, breaker.state("adyen", "US"));
    }

    @Test
    void halfOpenProbeClosesOnSuccessAndLengthensCooldownOnFailure() {
        MutableClock clock = clock();
        CorridorCircuitBreaker breaker = breaker(clock);
        trip(breaker);
        assertFalse(breaker.allow("adyen", "EU"));
        clock.advance(Duration.ofSeconds(30));
        assertTrue(breaker.allow("adyen", "EU"));
        assertEquals(CorridorCircuitBreaker.State.HALF_OPEN, breaker.state("adyen", "EU"));
        breaker.record("adyen", "EU", false);
        assertEquals(CorridorCircuitBreaker.State.CLOSED, breaker.state("adyen", "EU"));
        assertTrue(breaker.eurOptionsEnabled());

        trip(breaker);
        clock.advance(Duration.ofSeconds(30));
        assertTrue(breaker.allow("adyen", "EU"));
        breaker.record("adyen", "EU", true);
        assertEquals(CorridorCircuitBreaker.State.OPEN, breaker.state("adyen", "EU"));
        clock.advance(Duration.ofSeconds(30));
        assertFalse(breaker.allow("adyen", "EU"));
        clock.advance(Duration.ofSeconds(30));
        assertTrue(breaker.allow("adyen", "EU"));
    }

    private static void trip(CorridorCircuitBreaker breaker) {
        for (int i = 0; i < 20; i++) breaker.record("adyen", "EU", true);
    }

    private static CorridorCircuitBreaker breaker(Clock clock) {
        return new CorridorCircuitBreaker(new CircuitBreakerSettings(0.05, 60, 20, 30), clock);
    }

    private static MutableClock clock() {
        return new MutableClock();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-18T12:00:00Z");

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}

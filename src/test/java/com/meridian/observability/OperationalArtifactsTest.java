package com.meridian.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationalArtifactsTest {
    @Test
    void alertRulesCoverScaAndProviderTimeoutsOverFiveMinutes() throws Exception {
        String rules = Files.readString(Path.of("ops/prometheus/alerts.yml"));
        Map<String, Object> parsed = new Yaml().load(rules);
        assertTrue(parsed.containsKey("groups"));
        assertTrue(rules.contains("sca_step_up_success_total"));
        assertTrue(rules.contains("sca_step_up_attempts_total"));
        assertTrue(rules.contains("< 0.92"));
        assertTrue(rules.contains("for: 5m"));
        assertTrue(rules.contains("payment_provider_timeouts_total"));
        assertTrue(rules.contains("payment_submissions_total"));
        assertTrue(rules.contains("> 0.015"));
        assertTrue(rules.contains("[5m]"));
        String scrape = Files.readString(Path.of("ops/prometheus/prometheus.yml"));
        assertTrue(scrape.contains("alerts.yml"));
        assertTrue(scrape.contains("/actuator/prometheus"));
    }

    @Test
    void grafanaDashboardTracksAvailabilityAndSepaLatency() throws Exception {
        JsonNode dashboard = new ObjectMapper().readTree(Path.of("ops/grafana/meridian-sepa-slo.json").toFile());
        String text = dashboard.toString();
        assertTrue(text.contains("99.95%"));
        assertTrue(text.contains("0.9995"));
        assertTrue(text.contains("800ms SEPA Instant SLO"));
        assertTrue(text.contains("histogram_quantile(0.95"));
        assertTrue(text.contains("payment_authorization_seconds_bucket"));
        assertTrue(dashboard.toString().contains("\"value\":0.8") || text.contains("\"value\":0.8"));
        assertTrue(text.contains("payment_submissions_total"));
        assertTrue(text.contains("sca_step_up_success_rate"));
        assertTrue(text.contains("circuit_breaker_state"));
        assertTrue(text.contains("0.92"));
        assertTrue(text.contains("0.015"));
    }
}

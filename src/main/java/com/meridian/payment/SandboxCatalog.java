package com.meridian.payment;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixture tables for the in-process provider sandbox. Adyen and Worldpay only. */
public final class SandboxCatalog {
  public record Token(String outcome, String flow) {}
  public record ProviderSpec(
    String settledCode,
    Set<String> softCodes,
    Set<String> hardCodes,
    String softScenarioCode,
    String hardScenarioCode,
    String unknownScenarioCode
  ) {}

  private static final SandboxCatalog INSTANCE = load();
  private final Map<String, Token> tokens;
  private final Map<String, ProviderSpec> providers;

  private SandboxCatalog(Map<String, Token> tokens, Map<String, ProviderSpec> providers) {
    this.tokens = tokens;
    this.providers = providers;
  }

  public static SandboxCatalog get() { return INSTANCE; }

  public Token token(String value) { return tokens.get(value); }

  public ProviderSpec provider(String providerId) { return providers.get(providerId); }

  public Set<String> providerIds() { return Set.copyOf(providers.keySet()); }

  @SuppressWarnings("unchecked")
  private static SandboxCatalog load() {
    try (InputStream stream = SandboxCatalog.class.getResourceAsStream("/sandbox-fixtures.json")) {
      if (stream == null) throw new IllegalStateException("sandbox-fixtures.json not found");
      Map<String, Object> root = new ObjectMapper().readValue(stream, Map.class);
      Map<String, Token> tokens = new LinkedHashMap<>();
      Map<String, Object> tokenNode = (Map<String, Object>) root.get("scaTokens");
      for (var entry : tokenNode.entrySet()) {
        Map<String, Object> body = (Map<String, Object>) entry.getValue();
        tokens.put(entry.getKey(), new Token((String) body.get("outcome"), (String) body.get("flow")));
      }
      Map<String, ProviderSpec> providers = new LinkedHashMap<>();
      Map<String, Object> providerNode = (Map<String, Object>) root.get("providers");
      for (var entry : providerNode.entrySet()) {
        Map<String, Object> body = (Map<String, Object>) entry.getValue();
        providers.put(entry.getKey(), new ProviderSpec(
          (String) body.get("settledCode"),
          Set.copyOf((List<String>) body.get("softCodes")),
          Set.copyOf((List<String>) body.get("hardCodes")),
          (String) body.get("softScenarioCode"),
          (String) body.get("hardScenarioCode"),
          (String) body.get("unknownScenarioCode")
        ));
      }
      if (!providers.keySet().equals(Set.of("adyen", "worldpay"))) {
        throw new IllegalStateException("Sandbox fixtures must list only adyen and worldpay");
      }
      return new SandboxCatalog(Map.copyOf(tokens), Map.copyOf(providers));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load sandbox fixtures", e);
    }
  }
}

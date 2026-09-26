package com.meridian.service;

import com.meridian.domain.Provider;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Binds each catalog method to exactly one provider adapter.
 * The catalog registry is the source of truth shared with mobile clients.
 * Composite client ids such as adyen_card are not methods on this map.
 */
public final class CatalogRouting {
  private final Map<String, String> methodToProvider;

  public CatalogRouting(List<Provider> catalogProviders, Set<String> adapterIds) {
    if (catalogProviders == null || catalogProviders.isEmpty()) {
      throw new IllegalStateException("Catalog provider registry is empty");
    }
    Set<String> adapters = Set.copyOf(adapterIds);
    Map<String, String> resolved = new LinkedHashMap<>();
    Set<String> catalogIds = new LinkedHashSet<>();
    for (Provider provider : catalogProviders) {
      String id = provider.getId();
      if (id == null || id.isBlank() || !adapters.contains(id)) {
        throw new IllegalStateException("Catalog provider is not backed by an adapter: " + id);
      }
      if (!catalogIds.add(id)) {
        throw new IllegalStateException("Duplicate catalog provider: " + id);
      }
      List<String> methods = provider.getMethods();
      if (methods == null || methods.isEmpty()) {
        throw new IllegalStateException("Catalog provider has no methods: " + id);
      }
      for (String method : methods) {
        if (method == null || method.isBlank()) {
          throw new IllegalStateException("Catalog provider has a blank method: " + id);
        }
        String previous = resolved.putIfAbsent(method, id);
        if (previous != null) {
          throw new IllegalStateException("Payment method maps to more than one provider: " + method);
        }
      }
    }
    if (!catalogIds.equals(adapters)) {
      throw new IllegalStateException("Provider adapter is missing from the catalog registry");
    }
    this.methodToProvider = Map.copyOf(resolved);
  }

  public boolean supportsMethod(String method) {
    return method != null && methodToProvider.containsKey(method);
  }

  public String providerIdForMethod(String method) {
    return methodToProvider.get(method);
  }
}

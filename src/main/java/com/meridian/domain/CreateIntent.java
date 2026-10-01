package com.meridian.domain;

import java.util.Map;

/** Closed v2 create body. Provider selection is not a client field. */
public record CreateIntent(
    String recipientId,
    Long amountMinor,
    String methodId,
    String corridor,
    String note,
    String scenario,
    String catalogVersion,
    Map<String, Object> descriptor) {}

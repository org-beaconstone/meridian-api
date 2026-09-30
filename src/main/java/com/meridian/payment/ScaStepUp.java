package com.meridian.payment;

/** Synthetic step-up check. Tokens are fixture names, not credentials or card data. */
public final class ScaStepUp {
  public enum Verdict { AUTHENTICATED, CHALLENGE_FAILED, EXPIRED, MALFORMED }

  public record Result(Verdict verdict, String flow) {}

  private ScaStepUp() {}

  public static Result verify(String token) {
    if (token == null || !token.matches("sca_[A-Za-z0-9_-]{3,64}")) return new Result(Verdict.MALFORMED, "");
    var known = SandboxCatalog.get().token(token);
    if (known == null) return new Result(Verdict.MALFORMED, "");
    return switch (known.outcome()) {
      case "AUTHENTICATED" -> new Result(Verdict.AUTHENTICATED, known.flow());
      case "CHALLENGE_FAILED" -> new Result(Verdict.CHALLENGE_FAILED, known.flow());
      case "EXPIRED" -> new Result(Verdict.EXPIRED, known.flow());
      default -> new Result(Verdict.MALFORMED, "");
    };
  }
}

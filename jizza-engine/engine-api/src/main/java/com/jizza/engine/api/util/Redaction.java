package com.jizza.engine.api.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Last-line-of-defence scrubbing for anything about to be logged or placed in LLM context (R11/R13).
 * The real defence is that secrets are never fetched into those paths at all; this catches mistakes.
 */
public final class Redaction {

  private Redaction() {}

  public static final String MASK = "[redacted]";

  private static final List<Pattern> PATTERNS = List.of(
      Pattern.compile("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]{8,}"),
      Pattern.compile("(?i)\"(access_token|refresh_token|client_secret|password|api_key|apikey|authorization|mfa_secret|code_verifier)\"\\s*:\\s*\"[^\"]*\""),
      Pattern.compile("(?i)\\b(access_token|refresh_token|client_secret|api_key|apikey)=[^&\\s]+"),
      Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}"),
      Pattern.compile("\\bghp_[A-Za-z0-9]{20,}"),
      Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")
  );

  public static String scrub(String input) {
    if (input == null || input.isEmpty()) return input;
    String out = input;
    out = PATTERNS.get(0).matcher(out).replaceAll("Bearer " + MASK);
    out = PATTERNS.get(1).matcher(out).replaceAll(m -> "\"" + m.group(1) + "\":\"" + MASK + "\"");
    out = PATTERNS.get(2).matcher(out).replaceAll(m -> m.group(1) + "=" + MASK);
    for (int i = 3; i < PATTERNS.size(); i++) {
      out = PATTERNS.get(i).matcher(out).replaceAll(MASK);
    }
    return out;
  }

  /** True when the text still looks like it contains credential material. */
  public static boolean containsSecretLike(String input) {
    if (input == null) return false;
    for (Pattern p : PATTERNS) {
      var m = p.matcher(input);
      while (m.find()) {
        if (!m.group().contains(MASK)) return true;
      }
    }
    return false;
  }
}

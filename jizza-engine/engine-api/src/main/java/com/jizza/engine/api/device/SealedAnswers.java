package com.jizza.engine.api.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import java.util.regex.Pattern;

/**
 * Secret answers sealed end to end (plan 05, option B): a device asking for a secret answer sends
 * the public half of a one-off ECDH P-256 key; the card seals the answer to it in the person's
 * browser ({@code {v: 1, epk, iv, ct}}, base64url). The server only checks the shape and relays
 * it: it holds no key that opens it.
 */
public final class SealedAnswers {

  private SealedAnswers() {}

  /** An uncompressed P-256 point, base64url: 65 bytes is 87 characters. */
  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{87}");
  private static final Pattern IV = Pattern.compile("[A-Za-z0-9_-]{16}");
  /** 4,000 characters of answer (a pasted sign-in link) at most 4 bytes each, plus the 16-byte
   *  tag. */
  private static final Pattern CT = Pattern.compile("[A-Za-z0-9_-]{22,21400}");

  public static boolean isKey(String value) {
    return value != null && KEY.matcher(value).matches();
  }

  /** The sealed answer as the device takes it, or null when it is not one. */
  public static ObjectNode of(JsonNode raw) {
    if (raw == null || !raw.isObject() || raw.path("v").asInt(0) != 1) return null;
    String epk = raw.path("epk").asText("");
    String iv = raw.path("iv").asText("");
    String ct = raw.path("ct").asText("");
    if (!isKey(epk) || !IV.matcher(iv).matches() || !CT.matcher(ct).matches()) return null;
    return Json.obj().put("v", 1).put("epk", epk).put("iv", iv).put("ct", ct);
  }
}

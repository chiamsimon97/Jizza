package com.jizza.security.mfa;

import java.nio.charset.StandardCharsets;

/** RFC 4648 base32, the encoding authenticator apps expect for a TOTP seed. */
public final class Base32 {

  private Base32() {}

  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  public static String encode(byte[] data) {
    StringBuilder out = new StringBuilder();
    int buffer = 0;
    int bits = 0;
    for (byte b : data) {
      buffer = (buffer << 8) | (b & 0xFF);
      bits += 8;
      while (bits >= 5) {
        out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1F));
        bits -= 5;
      }
    }
    if (bits > 0) out.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
    return out.toString();
  }

  public static byte[] decode(String encoded) {
    String clean = encoded.replace("=", "").replace(" ", "").toUpperCase(java.util.Locale.ROOT);
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    int buffer = 0;
    int bits = 0;
    for (char c : clean.toCharArray()) {
      int idx = ALPHABET.indexOf(c);
      if (idx < 0) throw new IllegalArgumentException("Not a base32 character: " + c);
      buffer = (buffer << 5) | idx;
      bits += 5;
      if (bits >= 8) {
        out.write((buffer >> (bits - 8)) & 0xFF);
        bits -= 8;
      }
    }
    return out.toByteArray();
  }

  public static String encodeUtf8(String s) { return encode(s.getBytes(StandardCharsets.UTF_8)); }
}

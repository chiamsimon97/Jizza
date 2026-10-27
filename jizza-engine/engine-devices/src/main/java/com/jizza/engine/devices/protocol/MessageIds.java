package com.jizza.engine.devices.protocol;

import java.security.SecureRandom;
import java.time.Instant;

/** ULIDs: unique, and sortable by when they were made, which keeps logs readable. */
public final class MessageIds {

  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final SecureRandom RANDOM = new SecureRandom();

  private MessageIds() {}

  public static String next() {
    long time = Instant.now().toEpochMilli();
    char[] out = new char[26];
    for (int i = 9; i >= 0; i--) {
      out[i] = ALPHABET[(int) (time & 31)];
      time >>>= 5;
    }
    for (int i = 10; i < 26; i++) out[i] = ALPHABET[RANDOM.nextInt(32)];
    return new String(out);
  }
}

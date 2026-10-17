package com.jizza.engine.persistence.support;

import java.util.StringJoiner;

/**
 * pgvector literal helpers. Embeddings are bound to native queries as a {@code vector} literal
 * because the {@code <=>} distance operator has no JPA equivalent.
 */
public final class Vectors {

  private Vectors() {}

  public static final int DIMENSIONS = 1536;

  public static String toLiteral(float[] v) {
    if (v == null) return null;
    StringJoiner j = new StringJoiner(",", "[", "]");
    for (float f : v) j.add(Float.toString(f));
    return j.toString();
  }

  public static float[] parse(String literal) {
    if (literal == null || literal.isBlank()) return null;
    String body = literal.trim();
    if (body.startsWith("[")) body = body.substring(1);
    if (body.endsWith("]")) body = body.substring(0, body.length() - 1);
    if (body.isBlank()) return new float[0];
    String[] parts = body.split(",");
    float[] out = new float[parts.length];
    for (int i = 0; i < parts.length; i++) out[i] = Float.parseFloat(parts[i].trim());
    return out;
  }

  /** Pads or truncates so a provider with a different embedding width still fits the column. */
  public static float[] fit(float[] v) {
    if (v == null) return null;
    if (v.length == DIMENSIONS) return v;
    float[] out = new float[DIMENSIONS];
    System.arraycopy(v, 0, out, 0, Math.min(v.length, DIMENSIONS));
    return out;
  }
}

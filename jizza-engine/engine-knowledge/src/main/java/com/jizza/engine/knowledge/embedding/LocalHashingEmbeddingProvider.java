package com.jizza.engine.knowledge.embedding;

import com.jizza.engine.persistence.support.Vectors;
import java.util.Locale;

/**
 * A deterministic, offline embedding: hashed character n-grams projected into the column width and
 * L2-normalised, so cosine distance behaves.
 *
 * <p>It is a genuine lexical-similarity signal rather than a placeholder, and it keeps the
 * local-first promise -- knowledge about health and finances never leaves the machine just to be
 * indexed. Swap in a hosted model by contributing another {@link EmbeddingProvider} bean.
 */
public class LocalHashingEmbeddingProvider implements EmbeddingProvider {

  private static final int NGRAM = 3;

  @Override
  public int dimensions() { return Vectors.DIMENSIONS; }

  @Override
  public float[] embed(String text) {
    float[] vector = new float[dimensions()];
    if (text == null || text.isBlank()) return vector;

    String normalised = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    for (String token : normalised.split(" ")) {
      accumulate(vector, "w:" + token, 1.0f);
      String padded = "^" + token + "$";
      for (int i = 0; i + NGRAM <= padded.length(); i++) {
        accumulate(vector, "g:" + padded.substring(i, i + NGRAM), 0.5f);
      }
    }
    return normalise(vector);
  }

  private void accumulate(float[] vector, String feature, float weight) {
    int hash = feature.hashCode();
    int index = Math.floorMod(hash, vector.length);
    // A second hash bit decides the sign, which keeps unrelated features from always reinforcing.
    float sign = ((hash >>> 31) & 1) == 0 ? 1f : -1f;
    vector[index] += sign * weight;
  }

  private float[] normalise(float[] vector) {
    double sumSquares = 0;
    for (float v : vector) sumSquares += (double) v * v;
    if (sumSquares == 0) return vector;
    float norm = (float) Math.sqrt(sumSquares);
    for (int i = 0; i < vector.length; i++) vector[i] /= norm;
    return vector;
  }
}

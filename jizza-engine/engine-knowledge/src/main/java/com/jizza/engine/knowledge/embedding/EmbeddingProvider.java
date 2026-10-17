package com.jizza.engine.knowledge.embedding;

/**
 * Produces the vector stored in {@code knowledge_entries.embedding} and {@code memories.embedding}.
 * A separate interface so a hosted embedding model can replace the local one without touching the
 * stores.
 */
public interface EmbeddingProvider {

  float[] embed(String text);

  /** Width of the vectors this provider emits; must fit the column. */
  int dimensions();

  /**
   * Identifies the model behind the vectors. Vectors from different models are not comparable, so
   * stores that record it only compare like with like and re-embed the rest.
   */
  default String id() { return getClass().getSimpleName(); }
}

package com.jizza.engine.knowledge.embedding;

/**
 * Produces the vector stored in {@code knowledge_entries.embedding}. A separate interface so a
 * hosted embedding model can replace the local one without touching the store.
 */
public interface EmbeddingProvider {

  float[] embed(String text);

  /** Width of the vectors this provider emits; must fit the column. */
  int dimensions();
}

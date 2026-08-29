package com.jizza.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.persistence.support.Vectors;
import org.junit.jupiter.api.Test;

class LocalHashingEmbeddingProviderTest {

  private final LocalHashingEmbeddingProvider provider = new LocalHashingEmbeddingProvider();

  private double cosine(float[] a, float[] b) {
    double dot = 0;
    for (int i = 0; i < a.length; i++) dot += (double) a[i] * b[i];
    return dot;
  }

  @Test
  void emitsVectorsThatFitTheColumn() {
    assertThat(provider.embed("resting heart rate")).hasSize(Vectors.DIMENSIONS);
    assertThat(provider.dimensions()).isEqualTo(Vectors.DIMENSIONS);
  }

  @Test
  void isDeterministic() {
    assertThat(provider.embed("blood pressure reading"))
        .isEqualTo(provider.embed("blood pressure reading"));
  }

  @Test
  void scoresRelatedTextHigherThanUnrelatedText() {
    float[] query = provider.embed("resting heart rate");
    double related = cosine(query, provider.embed("my resting heart rate this week"));
    double unrelated = cosine(query, provider.embed("quarterly mutual fund statement"));
    assertThat(related).isGreaterThan(unrelated);
  }

  @Test
  void handlesEmptyInput() {
    assertThat(provider.embed("")).hasSize(Vectors.DIMENSIONS);
    assertThat(provider.embed(null)).hasSize(Vectors.DIMENSIONS);
  }
}

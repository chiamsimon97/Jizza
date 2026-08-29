package com.jizza.persistence.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VectorsTest {

  @Test
  void roundTripsAVector() {
    float[] v = {0.5f, -0.25f, 1.0f};
    assertThat(Vectors.toLiteral(v)).isEqualTo("[0.5,-0.25,1.0]");
    assertThat(Vectors.parse(Vectors.toLiteral(v))).containsExactly(v);
  }

  @Test
  void fitsShorterAndLongerEmbeddingsToTheColumnWidth() {
    assertThat(Vectors.fit(new float[] {1f, 2f})).hasSize(Vectors.DIMENSIONS);
    assertThat(Vectors.fit(new float[] {1f, 2f})[0]).isEqualTo(1f);
    assertThat(Vectors.fit(new float[Vectors.DIMENSIONS + 10])).hasSize(Vectors.DIMENSIONS);
  }

  @Test
  void handlesNullAndEmpty() {
    assertThat(Vectors.toLiteral(null)).isNull();
    assertThat(Vectors.parse(null)).isNull();
    assertThat(Vectors.parse("[]")).isEmpty();
  }
}

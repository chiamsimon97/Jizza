package com.jizza.engine.api.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FuzzyTextTest {

  @Test
  void wordsMeetAcrossSpellingsPrefixesAndTypos() {
    assertThat(FuzzyText.near("ramayana", "ramayanam")).isTrue();
    assertThat(FuzzyText.near(FuzzyText.stem("booking"), "book")).isTrue();
    assertThat(FuzzyText.near("invoice", "invoce")).isTrue();
    assertThat(FuzzyText.near("gold", "bold")).as("short words must match exactly").isFalse();
    assertThat(FuzzyText.near("table", "ticket")).isFalse();
    assertThat(FuzzyText.stem("movies")).isEqualTo(FuzzyText.stem("movie"));
  }

  @Test
  void fillerAndPointingWordsAreDropped() {
    assertThat(FuzzyText.terms("Can you run the workflow I created")).isEmpty();
    assertThat(FuzzyText.coverage("run my gold rate workflow", "Gold rate checker")).isEqualTo(1.0);
  }
}

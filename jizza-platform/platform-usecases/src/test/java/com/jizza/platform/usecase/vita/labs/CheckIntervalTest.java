package com.jizza.platform.usecase.vita.labs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.util.Json;
import java.time.Period;
import org.junit.jupiter.api.Test;

/** Intervals are read only from what config says; a check needs a date. */
class CheckIntervalTest {

  @Test
  void anIntervalIsReadOnlyFromWhatConfigSays() {
    assertThat(VitaCheckUseCase.period(Json.read("{\"timesPerYearAtLeast\": 1}"))).isEqualTo(Period.ofMonths(12));
    assertThat(VitaCheckUseCase.period(Json.read("{\"timesPerYearAtLeast\": 2}"))).isEqualTo(Period.ofMonths(6));
    assertThat(VitaCheckUseCase.period(Json.read("{\"aboutEveryMonths\": 3}"))).isEqualTo(Period.ofMonths(3));
    assertThat(VitaCheckUseCase.period(Json.read("{\"timesPerYearAtLeast\": 5}"))).isNull();
    assertThat(VitaCheckUseCase.period(Json.read("{}"))).isNull();
    assertThat(VitaCheckUseCase.period(null)).isNull();
  }

  @Test
  void aCheckNeedsADateAndACustomOneAName() {
    assertThatThrownBy(() -> VitaCheckUseCase.validate(Json.read("{\"kind\": \"eye\"}"), null)).hasMessageContaining("date");
    assertThatThrownBy(() -> VitaCheckUseCase.validate(Json.read("{\"kind\": \"custom\", \"doneOn\": \"2026-01-01\"}"), null))
        .hasMessageContaining("Name");
    VitaCheckUseCase.validate(Json.read("{\"kind\": \"eye\", \"nextDueOn\": \"2027-01-01\"}"), null);
  }
}

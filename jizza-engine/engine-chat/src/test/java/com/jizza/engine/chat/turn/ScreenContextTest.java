package com.jizza.engine.chat.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.Json;
import org.junit.jupiter.api.Test;

class ScreenContextTest {

  @Test
  void theTimeZoneIsNotPartOfTheScreen() {
    assertThat(TurnRunner.screenContext(Json.obj().put("timeZone", "Asia/Kolkata"))).isNull();
    assertThat(TurnRunner.screenContext(Json.obj().put("timeZone", "UTC").put("stepId", "n1")).toString())
        .isEqualTo("{\"stepId\":\"n1\"}");
    assertThat(TurnRunner.screenContext(null)).isNull();
  }
}

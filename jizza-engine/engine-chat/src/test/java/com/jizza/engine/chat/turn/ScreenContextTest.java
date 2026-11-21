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

  @Test
  void earlierAnswersAreKeptForTheReplyButNeverShownToTheModel() {
    var context = Json.obj().put("timeZone", "UTC").put("depth", "deep");
    var versions = context.putArray("versions");
    versions.addObject().put("text", "First answer");
    versions.addObject().put("text", "  ");
    versions.addObject().put("text", "Second answer").put("deep", true);

    assertThat(TurnRunner.screenContext(context).toString()).isEqualTo("{\"depth\":\"deep\"}");
    assertThat(TurnRunner.earlierVersions(context).toString())
        .isEqualTo("[{\"text\":\"First answer\"},{\"text\":\"Second answer\",\"deep\":true}]");
  }

  @Test
  void onlyTheLastFewEarlierAnswersAreKeptAndEachIsBounded() {
    var context = Json.obj();
    var versions = context.putArray("versions");
    for (int i = 0; i < 8; i++) versions.addObject().put("text", "v" + i);
    versions.addObject().put("text", "x".repeat(TurnRunner.MAX_VERSION_CHARS + 50));

    var kept = TurnRunner.earlierVersions(context);
    assertThat(kept).hasSize(TurnRunner.MAX_VERSIONS);
    assertThat(kept.get(0).path("text").asText()).isEqualTo("v4");
    assertThat(kept.get(kept.size() - 1).path("text").asText()).hasSize(TurnRunner.MAX_VERSION_CHARS);
    assertThat(TurnRunner.earlierVersions(Json.obj().put("stepId", "n1"))).isNull();
    assertThat(TurnRunner.earlierVersions(null)).isNull();
  }
}

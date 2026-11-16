package com.jizza.engine.orchestrator.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.Json;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunRequestReaderTest {

  @Test
  void readsOnlyAClearRunAnswer() {
    var reading = RunRequestReader.parse("Here you go: {\"run\": true, \"workflow\": 2, \"values\": {\"to\": \"NDLS\"}}", 2);
    assertThat(reading.index()).isEqualTo(1);
    assertThat(reading.values().path("to").asText()).isEqualTo("NDLS");
    assertThat(RunRequestReader.parse("{\"run\": false, \"workflow\": 1}", 2)).isNull();
    assertThat(RunRequestReader.parse("{\"run\": true, \"workflow\": 3}", 2)).isNull();
    assertThat(RunRequestReader.parse("{\"run\": true}", 2)).isNull();
    assertThat(RunRequestReader.parse("{not json}", 2)).isNull();
    assertThat(RunRequestReader.parse(null, 2)).isNull();
    assertThat(RunRequestReader.parse("{\"run\": true, \"workflow\": 1, \"values\": [1]}", 1).values().isObject()).isTrue();
  }

  @Test
  void describesTheWorkflowsAndToday() {
    var inputs = RunInputs.of(Json.read(ChatRunPlannerTest.GRAPH), null);
    String text = RunRequestReader.describe("book it", List.of(
        new RunRequestReader.Candidate("Simon", "Books a train ticket", inputs),
        new RunRequestReader.Candidate("Report", null, List.of())), ZoneId.of("Asia/Kolkata"));
    assertThat(text).startsWith("Today is ").contains("(Asia/Kolkata)")
        .contains("1. Simon — Books a train ticket")
        .contains("date (date as YYYY-MM-DD, needed)")
        .contains("passengers (list of rows, needed); each row has: name, age [number]")
        .contains("2. Report").contains("(takes no inputs)")
        .endsWith("The message:\nbook it");
  }
}

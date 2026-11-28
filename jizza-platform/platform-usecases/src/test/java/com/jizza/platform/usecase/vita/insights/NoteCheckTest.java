package com.jizza.platform.usecase.vita.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.vita.insights.NoteCheck.Ref;
import com.jizza.platform.usecase.vita.insights.NoteCheck.Sentence;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every sentence of the weekly note has rows behind it and no number those rows do not hold. */
class NoteCheckTest {

  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  private static final String BP = "0b7e2c1a-1111-4a2b-9c3d-123456789012";
  private static final String DOSE_A = "9a2d0000-0000-4000-8000-000000000001";
  private static final String DOSE_B = "9a2d0000-0000-4000-8000-000000000002";

  private static Map<String, ObjectNode> rows() {
    return Map.of(
        BP, Json.obj().put("id", BP).put("kind", "bp").put("value", 148).put("value2", 94)
            .put("takenAt", "2026-12-03T03:30:00Z").put("personId", "6f1c0000-0000-4000-8000-000000000003")
            .put("createdAt", "2026-12-17T10:00:00Z"),
        DOSE_A, Json.obj().put("id", DOSE_A).put("dueAt", "2026-12-04T02:30:00Z").put("status", "missed"),
        DOSE_B, Json.obj().put("id", DOSE_B).put("dueAt", "2026-12-05T02:30:00Z").put("status", "missed"));
  }

  @Test
  void aSentenceWithoutRowIdsIsRejected() {
    assertThat(NoteCheck.problem(new Sentence("missed_doses", "2 doses were not marked as taken this week.", List.of()),
        rows(), INDIA)).isEqualTo("no row behind it");
    assertThat(NoteCheck.problem(new Sentence("missed_doses", "2 doses were not marked as taken this week.", null),
        rows(), INDIA)).isEqualTo("no row behind it");
  }

  @Test
  void aSentenceNamingARowTheNoteWasNotBuiltFromIsRejected() {
    assertThat(NoteCheck.problem(new Sentence("visit", "A visit is planned.",
        List.of(new Ref("vita_visit", "11111111-1111-4111-8111-111111111111"))), rows(), INDIA))
        .contains("not one the note was built from");
  }

  @Test
  void everyNumberMustBeTheCountOrAValueOrADatePartOfItsRows() {
    Ref bp = new Ref("vita_measurement", BP);
    assertThat(NoteCheck.problem(new Sentence("outside_range",
        "1 blood pressure reading was outside the usual range: 148/94 on 3 Dec.", List.of(bp)), rows(), INDIA)).isNull();
    // A number not in the row: a model that made up a reading is caught.
    assertThat(NoteCheck.problem(new Sentence("outside_range",
        "1 blood pressure reading was outside the usual range: 158/94 on 3 Dec.", List.of(bp)), rows(), INDIA))
        .contains("158");
    // Neither the digits of an id nor the time a row was written vouch for a number.
    assertThat(NoteCheck.problem(new Sentence("outside_range", "Reading 123456789012 on 3 Dec.", List.of(bp)), rows(), INDIA))
        .contains("123456789012");
    assertThat(NoteCheck.problem(new Sentence("outside_range", "Seen on 17 Dec.", List.of(bp)), rows(), INDIA))
        .contains("17");
    // The count of the rows is allowed; a wrong count is not.
    List<Ref> two = List.of(new Ref("vita_dose_log", DOSE_A), new Ref("vita_dose_log", DOSE_B));
    assertThat(NoteCheck.problem(new Sentence("missed_doses", "2 doses were not marked as taken this week.", two), rows(), INDIA)).isNull();
    assertThat(NoteCheck.problem(new Sentence("missed_doses", "3 doses were not marked as taken this week.", two), rows(), INDIA))
        .contains("3");
  }

  @Test
  void textTheRecordHoldsCarriesItsNumbers() {
    String med = "aa000000-0000-4000-8000-000000000009";
    Map<String, ObjectNode> rows = Map.of(med, Json.obj().put("id", med).put("name", "Metformin 500"));
    assertThat(NoteCheck.problem(new Sentence("refill", "Metformin 500 is running low: time to refill.",
        List.of(new Ref("vita_medicine", med))), rows, INDIA)).isNull();
  }

  @Test
  void spearmanRanksTiesAndSaysNothingWhenOneSideNeverVaries() {
    assertThat(VitaCorrelationService.spearman(new double[] {1, 2, 3, 4}, new double[] {10, 20, 30, 40})).isEqualTo(1.0);
    assertThat(VitaCorrelationService.spearman(new double[] {1, 2, 3, 4}, new double[] {40, 30, 20, 10})).isEqualTo(-1.0);
    assertThat(VitaCorrelationService.spearman(new double[] {1, 2, 3}, new double[] {5, 5, 5})).isNull();
    assertThat(VitaCorrelationService.ranks(new double[] {3, 1, 3, 2})).containsExactly(3.5, 1.0, 3.5, 2.0);
  }
}

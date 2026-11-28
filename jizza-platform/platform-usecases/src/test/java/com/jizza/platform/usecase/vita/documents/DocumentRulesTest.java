package com.jizza.platform.usecase.vita.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What an upload may be, and what a confirmed prescription saves. */
class DocumentRulesTest {

  private static final VitaDocumentUseCase.Limits LIMITS = new VitaDocumentUseCase.Limits(30, 20, 10 * 1024 * 1024, 5 * 1024 * 1024);
  private static final byte[] PDF = "%PDF-1.7 x".getBytes();
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

  @Test
  void onlyWhatTheModelCanTakeIsStored() {
    assertThat(VitaDocumentUseCase.checkFile("application/pdf", PDF, LIMITS)).isEqualTo("application/pdf");
    assertThat(VitaDocumentUseCase.checkFile("image/png", PNG, LIMITS)).isEqualTo("image/png");
    assertThat(VitaDocumentUseCase.checkFile("application/octet-stream", PDF, LIMITS)).isEqualTo("application/pdf");
    assertThatThrownBy(() -> VitaDocumentUseCase.checkFile("image/heic", new byte[] {1, 2, 3}, LIMITS))
        .isInstanceOf(ValidationException.class).hasMessageContaining("HEIC");
    assertThatThrownBy(() -> VitaDocumentUseCase.checkFile("text/plain", "hello".getBytes(), LIMITS))
        .hasMessageContaining("PDFs and photos");
    // A file that says it is a PDF but is not one.
    assertThatThrownBy(() -> VitaDocumentUseCase.checkFile("application/pdf", PNG, LIMITS))
        .hasMessageContaining("PDFs and photos");
    assertThatThrownBy(() -> VitaDocumentUseCase.checkFile("application/pdf", new byte[0], LIMITS))
        .hasMessageContaining("empty");
    byte[] bigPhoto = new byte[5 * 1024 * 1024 + 1];
    System.arraycopy(PNG, 0, bigPhoto, 0, PNG.length);
    assertThatThrownBy(() -> VitaDocumentUseCase.checkFile("image/png", bigPhoto, LIMITS))
        .hasMessageContaining("under 5 MB");
    byte[] bigPdf = new byte[10 * 1024 * 1024 + 1];
    System.arraycopy(PDF, 0, bigPdf, 0, PDF.length);
    assertThatThrownBy(() -> VitaDocumentUseCase.checkFile("application/pdf", bigPdf, LIMITS))
        .hasMessageContaining("10 MB");
  }

  @Test
  void aConfirmedPrescriptionSavesOnlyNamedMedicinesWithTimes() {
    var rows = Json.read("""
        [{"name": "Tab. Metformin", "strength": "500 mg", "form": "tablet", "startDate": "2026-10-12", "endDate": "2026-11-10",
          "schedule": {"times": ["08:00", "21:00"], "recurrence": "FREQ=DAILY", "doseUnits": 1, "doseUnit": "tablet", "withFood": "after"}},
         {"name": "", "skip": true},
         {"name": "Tab. Paracetamol", "asNeeded": true}]""");
    UUID doctor = UUID.randomUUID();
    var bodies = VitaDocumentUseCase.medicineBodies(rows, doctor, LocalDate.of(2026, 10, 12));
    assertThat(bodies).hasSize(2);
    assertThat(bodies.get(0).path("prescribedBy").asText()).isEqualTo(doctor.toString());
    assertThat(bodies.get(0).path("schedule").path("times")).hasSize(2);
    assertThat(bodies.get(1).path("asNeeded").asBoolean()).isTrue();
    assertThat(bodies.get(1).has("schedule")).isFalse();

    assertThatThrownBy(() -> VitaDocumentUseCase.medicineBodies(Json.read("[{\"name\": \"\", \"nameUnsure\": true}]"), null,
        LocalDate.of(2026, 10, 12))).hasMessageContaining("Type the name of medicine 1");
    assertThatThrownBy(() -> VitaDocumentUseCase.medicineBodies(Json.read("[{\"name\": \"Tab. X\", \"schedule\": null}]"), null,
        LocalDate.of(2026, 10, 12))).hasMessageContaining("Set the times for medicine 1");
  }
}

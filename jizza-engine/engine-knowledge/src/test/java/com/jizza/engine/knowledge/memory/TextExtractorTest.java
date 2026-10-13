package com.jizza.engine.knowledge.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/** What an agent can read out of the files it is given. */
class TextExtractorTest {

  @Test
  void readsPlainTextByTypeOrExtension() {
    byte[] notes = "Allergic to penicillin".getBytes(StandardCharsets.UTF_8);
    assertThat(TextExtractor.extract("notes.txt", "text/plain", notes))
        .isEqualTo("Allergic to penicillin");
    assertThat(TextExtractor.extract("budget.csv", "application/octet-stream", notes))
        .isEqualTo("Allergic to penicillin");
  }

  @Test
  void readsTheTextInsideAPdf() throws Exception {
    assertThat(TextExtractor.extract("report.pdf", "application/pdf", pdfSaying("Blood type O+")))
        .contains("Blood type O+");
  }

  @Test
  void keepsImagesAndBinaryAsUnreadable() {
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, (byte) 0xff, (byte) 0xfe};
    assertThat(TextExtractor.extract("scan.png", "image/png", png)).isEmpty();
    assertThat(TextExtractor.extract("fake.txt", "text/plain", png)).isEmpty();
    assertThat(TextExtractor.extract("broken.pdf", "application/pdf", png)).isEmpty();
  }

  @Test
  void capsVeryLongText() {
    byte[] big = "a".repeat(TextExtractor.MAX_CHARS + 50).getBytes(StandardCharsets.UTF_8);
    assertThat(TextExtractor.extract("big.txt", "text/plain", big)).hasSize(TextExtractor.MAX_CHARS);
  }

  private static byte[] pdfSaying(String text) throws Exception {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(72, 700);
        stream.showText(text);
        stream.endText();
      }
      doc.save(out);
      return out.toByteArray();
    }
  }
}

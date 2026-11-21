package com.jizza.engine.knowledge.memory;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Pulls the readable text out of an uploaded file so its agent can search it.
 *
 * <p>Plain-text formats are decoded as UTF-8, PDFs go through PDFBox, and Word, Excel and
 * PowerPoint files (.docx, .xlsx, .pptx) are read from their XML. Anything else (images, the older
 * .doc and .xls formats) yields nothing: the agent keeps the file, it just cannot read inside it.
 * Extraction never fails an upload; an unreadable file is stored without text.
 */
final class TextExtractor {

  /** Enough for a long document; beyond this the agent gets the start of it. */
  static final int MAX_CHARS = 200_000;

  private static final Set<String> TEXT_TYPES = Set.of(
      "application/json", "application/xml", "application/x-yaml", "application/yaml");
  private static final Set<String> TEXT_EXTENSIONS = Set.of(
      "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "log");

  static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

  private TextExtractor() {}

  static String extract(String filename, String contentType, byte[] bytes) {
    String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    String extension = extensionOf(filename);
    String text = null;
    if (type.equals("application/pdf") || extension.equals("pdf")) {
      text = pdf(bytes);
    } else if (extension.equals("docx") || type.equals(DOCX)) {
      text = OfficeText.docx(bytes);
    } else if (extension.equals("xlsx") || type.equals(XLSX)) {
      text = OfficeText.xlsx(bytes);
    } else if (extension.equals("pptx") || type.equals(PPTX)) {
      text = OfficeText.pptx(bytes);
    } else if (type.startsWith("text/") || TEXT_TYPES.contains(type)
        || TEXT_EXTENSIONS.contains(extension)) {
      text = utf8(bytes);
    }
    if (text == null) return "";
    text = text.strip();
    return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
  }

  private static String pdf(byte[] bytes) {
    try (PDDocument document = Loader.loadPDF(bytes)) {
      return new PDFTextStripper().getText(document);
    } catch (IOException | RuntimeException e) {
      // Encrypted or damaged PDFs are kept as files the agent cannot read.
      return null;
    }
  }

  /** Strict decoding, so a binary file mislabelled as text is not indexed as garbage. */
  private static String utf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      return null;
    }
  }

  private static String extensionOf(String filename) {
    if (filename == null) return "";
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }
}

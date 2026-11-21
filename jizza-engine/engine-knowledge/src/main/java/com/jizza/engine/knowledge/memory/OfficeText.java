package com.jizza.engine.knowledge.memory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The text of Word documents, Excel workbooks and PowerPoint decks (the .docx, .xlsx and .pptx
 * formats), read straight from their XML: a document's paragraphs, a workbook's sheets as rows of
 * tab-separated cells, a deck's slides in order. No formatting, formulas or pictures. Reading stops
 * at a bounded size so a crafted file cannot exhaust memory, and a file that is not what its name
 * says yields nothing.
 */
final class OfficeText {

  /** No single part of the file is read past this, uncompressed. */
  static final int MAX_PART_BYTES = 20 * 1024 * 1024;
  /** The whole file is not read past this, uncompressed, in all. */
  static final long MAX_TOTAL_BYTES = 60L * 1024 * 1024;

  private static final Pattern SHEET = Pattern.compile("xl/worksheets/sheet(\\d+)\\.xml");
  private static final Pattern SLIDE = Pattern.compile("ppt/slides/slide(\\d+)\\.xml");
  private static final XMLInputFactory XML = XMLInputFactory.newFactory();

  static {
    // The parts are untrusted: no DTDs, no external entities.
    XML.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    XML.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
  }

  private OfficeText() {}

  static String docx(byte[] bytes) {
    byte[] document = parts(bytes, name -> name.equals("word/document.xml")).get("word/document.xml");
    return document == null ? null : paragraphs(document, "p", "t", "tab", "br");
  }

  static String pptx(byte[] bytes) {
    Map<Integer, byte[]> slides = new TreeMap<>();
    parts(bytes, name -> SLIDE.matcher(name).matches()).forEach((name, xml) -> {
      Matcher m = SLIDE.matcher(name);
      if (m.matches()) slides.put(Integer.parseInt(m.group(1)), xml);
    });
    if (slides.isEmpty()) return null;
    StringBuilder out = new StringBuilder();
    slides.forEach((n, xml) -> {
      String text = paragraphs(xml, "p", "t", null, "br");
      if (text == null || text.isBlank()) return;
      out.append("## Slide ").append(n).append('\n').append(text.strip()).append("\n\n");
    });
    return out.toString();
  }

  static String xlsx(byte[] bytes) {
    Map<String, byte[]> parts = parts(bytes, name -> name.equals("xl/sharedStrings.xml")
        || name.equals("xl/workbook.xml") || SHEET.matcher(name).matches());
    List<String> shared = parts.containsKey("xl/sharedStrings.xml")
        ? sharedStrings(parts.get("xl/sharedStrings.xml")) : List.of();
    List<String> names = parts.containsKey("xl/workbook.xml")
        ? attributes(parts.get("xl/workbook.xml"), "sheet", "name") : List.of();
    Map<Integer, byte[]> sheets = new TreeMap<>();
    parts.forEach((name, xml) -> {
      Matcher m = SHEET.matcher(name);
      if (m.matches()) sheets.put(Integer.parseInt(m.group(1)), xml);
    });
    if (sheets.isEmpty()) return null;
    StringBuilder out = new StringBuilder();
    sheets.forEach((n, xml) -> {
      String title = n - 1 < names.size() ? names.get(n - 1) : "Sheet " + n;
      out.append("## Sheet: ").append(title).append('\n').append(rows(xml, shared)).append('\n');
    });
    return out.toString();
  }

  /** The parts whose names match, each read up to the limits. */
  private static Map<String, byte[]> parts(byte[] zip, java.util.function.Predicate<String> wanted) {
    Map<String, byte[]> out = new LinkedHashMap<>();
    long total = 0;
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        if (entry.isDirectory() || !wanted.test(entry.getName())) continue;
        byte[] part = readBounded(in, MAX_PART_BYTES);
        total += part.length;
        if (total > MAX_TOTAL_BYTES) break;
        out.put(entry.getName(), part);
      }
    } catch (IOException | IllegalArgumentException e) {
      return Map.of();
    }
    return out;
  }

  private static byte[] readBounded(InputStream in, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = in.read(buffer)) > 0) {
      if (out.size() + read > max) throw new IOException("part too large");
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  /**
   * The text of every {@code text} element, one line per {@code paragraph} element, with a tab
   * for {@code tab} and a line break for {@code lineBreak} (namespaces ignored).
   */
  private static String paragraphs(byte[] xml, String paragraph, String text, String tab, String lineBreak) {
    StringBuilder out = new StringBuilder();
    try {
      XMLStreamReader r = XML.createXMLStreamReader(new ByteArrayInputStream(xml));
      boolean inText = false;
      while (r.hasNext()) {
        int event = r.next();
        if (event == XMLStreamConstants.START_ELEMENT) {
          String name = r.getLocalName();
          if (name.equals(text)) inText = true;
          else if (name.equals(tab)) out.append('\t');
          else if (name.equals(lineBreak)) out.append('\n');
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          String name = r.getLocalName();
          if (name.equals(text)) inText = false;
          else if (name.equals(paragraph)) out.append('\n');
        } else if (inText && (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)) {
          out.append(r.getText());
        }
        if (out.length() > TextExtractor.MAX_CHARS) break;
      }
    } catch (XMLStreamException e) {
      return out.isEmpty() ? null : out.toString();
    }
    return out.toString().replaceAll("\n{3,}", "\n\n");
  }

  private static List<String> sharedStrings(byte[] xml) {
    List<String> out = new ArrayList<>();
    try {
      XMLStreamReader r = XML.createXMLStreamReader(new ByteArrayInputStream(xml));
      StringBuilder current = null;
      boolean inText = false;
      while (r.hasNext()) {
        int event = r.next();
        if (event == XMLStreamConstants.START_ELEMENT) {
          if (r.getLocalName().equals("si")) current = new StringBuilder();
          else if (r.getLocalName().equals("t")) inText = true;
          // Phonetic hints in East Asian workbooks are not part of the cell's text.
          else if (r.getLocalName().equals("rPh")) inText = false;
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          if (r.getLocalName().equals("si") && current != null) out.add(current.toString());
          else if (r.getLocalName().equals("t")) inText = false;
        } else if (inText && current != null && event == XMLStreamConstants.CHARACTERS) {
          current.append(r.getText());
        }
      }
    } catch (XMLStreamException e) {
      // Whatever was read before the damage is kept.
    }
    return out;
  }

  private static List<String> attributes(byte[] xml, String element, String attribute) {
    List<String> out = new ArrayList<>();
    try {
      XMLStreamReader r = XML.createXMLStreamReader(new ByteArrayInputStream(xml));
      while (r.hasNext()) {
        if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals(element)) {
          String value = r.getAttributeValue(null, attribute);
          if (value != null) out.add(value);
        }
      }
    } catch (XMLStreamException e) {
      // Sheets without names are numbered instead.
    }
    return out;
  }

  /** A sheet's rows, its cells separated by tabs; empty rows left out. */
  private static String rows(byte[] xml, List<String> shared) {
    StringBuilder out = new StringBuilder();
    try {
      XMLStreamReader r = XML.createXMLStreamReader(new ByteArrayInputStream(xml));
      List<String> row = null;
      String type = null;
      StringBuilder value = null;
      boolean inValue = false;
      while (r.hasNext()) {
        int event = r.next();
        if (event == XMLStreamConstants.START_ELEMENT) {
          switch (r.getLocalName()) {
            case "row" -> row = new ArrayList<>();
            case "c" -> {
              type = r.getAttributeValue(null, "t");
              value = new StringBuilder();
            }
            case "v", "t" -> inValue = value != null;
            default -> { }
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          switch (r.getLocalName()) {
            case "v", "t" -> inValue = false;
            case "c" -> {
              if (row != null && value != null) row.add(cell(type, value.toString(), shared));
              value = null;
            }
            case "row" -> {
              if (row != null && row.stream().anyMatch(c -> !c.isBlank())) {
                out.append(String.join("\t", row).stripTrailing()).append('\n');
              }
              row = null;
            }
            default -> { }
          }
        } else if (inValue && value != null && event == XMLStreamConstants.CHARACTERS) {
          value.append(r.getText());
        }
        if (out.length() > TextExtractor.MAX_CHARS) break;
      }
    } catch (XMLStreamException e) {
      // Whatever was read before the damage is kept.
    }
    return out.toString();
  }

  private static String cell(String type, String raw, List<String> shared) {
    String clean = raw.replace('\t', ' ').replace('\n', ' ').strip();
    if (!"s".equals(type)) return clean;
    try {
      int index = Integer.parseInt(clean);
      return index >= 0 && index < shared.size()
          ? shared.get(index).replace('\t', ' ').replace('\n', ' ').strip() : "";
    } catch (NumberFormatException e) {
      return "";
    }
  }
}

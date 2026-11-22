package com.jizza.engine.knowledge.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class OfficeTextTest {

  @Test
  void aWordDocumentGivesItsParagraphs() throws Exception {
    byte[] docx = zip(Map.of("word/document.xml", """
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
        <w:p><w:r><w:t>Trip plan</w:t></w:r></w:p>
        <w:p><w:r><w:t xml:space="preserve">Day 1:</w:t></w:r><w:r><w:tab/><w:t>Goa</w:t></w:r></w:p>
        </w:body></w:document>"""));

    assertThat(TextExtractor.extract("plan.docx", "application/octet-stream", docx))
        .isEqualTo("Trip plan\nDay 1:\tGoa");
  }

  @Test
  void aWorkbookGivesEachSheetAsRowsOfCells() throws Exception {
    Map<String, String> parts = new LinkedHashMap<>();
    parts.put("xl/workbook.xml", """
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheets>
        <sheet name="Budget" sheetId="1"/><sheet name="Notes" sheetId="2"/></sheets></workbook>""");
    parts.put("xl/sharedStrings.xml", """
        <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <si><t>Item</t></si><si><t>Cost</t></si><si><r><t>Rent</t></r><r><t> (May)</t></r></si></sst>""");
    parts.put("xl/worksheets/sheet1.xml", """
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
        <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
        <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>25000</v></c></row>
        <row r="3"><c r="A3"/></row>
        </sheetData></worksheet>""");
    parts.put("xl/worksheets/sheet2.xml", """
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
        <row r="1"><c r="A1" t="inlineStr"><is><t>Pay on the 1st</t></is></c></row>
        </sheetData></worksheet>""");

    assertThat(TextExtractor.extract("budget.xlsx", null, zip(parts))).isEqualTo("""
        ## Sheet: Budget
        Item\tCost
        Rent (May)\t25000

        ## Sheet: Notes
        Pay on the 1st""");
  }

  @Test
  void aDeckGivesItsSlidesInOrder() throws Exception {
    Map<String, String> parts = new LinkedHashMap<>();
    String ns = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"";
    parts.put("ppt/slides/slide10.xml", "<p:sld " + ns + "><a:p><a:r><a:t>Last</a:t></a:r></a:p></p:sld>");
    parts.put("ppt/slides/slide2.xml", "<p:sld " + ns + "><a:p><a:r><a:t>Second</a:t></a:r></a:p></p:sld>");
    String deck = TextExtractor.extract("deck.pptx", null, zip(parts));
    assertThat(deck).isEqualTo("## Slide 2\nSecond\n\n## Slide 10\nLast");
  }

  @Test
  void aFileThatIsNotWhatItsNameSaysGivesNothing() {
    assertThat(TextExtractor.extract("fake.docx", null, "not a zip".getBytes(StandardCharsets.UTF_8))).isEmpty();
  }

  @Test
  void entitiesInAPartAreNeverResolved() throws Exception {
    byte[] docx = zip(Map.of("word/document.xml", """
        <?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]>
        <w:document xmlns:w="w"><w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>"""));
    assertThat(TextExtractor.extract("evil.docx", null, docx)).doesNotContain("root:");
  }

  private static byte[] zip(Map<String, String> parts) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (var part : parts.entrySet()) {
        zip.putNextEntry(new ZipEntry(part.getKey()));
        zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }
}

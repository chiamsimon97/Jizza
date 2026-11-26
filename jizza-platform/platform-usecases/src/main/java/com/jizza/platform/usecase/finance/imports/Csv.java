package com.jizza.platform.usecase.finance.imports;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A small CSV reader for statement exports: quoted fields with doubled quotes, commas, semicolons or
 * tabs (whichever the file uses), CRLF or LF, and a byte-order mark. Text that is not UTF-8 is read
 * as Windows-1252, which is what spreadsheet apps save in India.
 */
final class Csv {

  private Csv() {}

  static List<List<String>> read(byte[] bytes, int maxRows) {
    String text = decode(bytes);
    if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
    char sep = separator(text);
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            cell.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          cell.append(c);
        }
      } else if (c == '"' && cell.toString().isBlank()) {
        cell.setLength(0);
        quoted = true;
      } else if (c == sep) {
        row.add(cell.toString().strip());
        cell.setLength(0);
      } else if (c == '\n' || c == '\r') {
        if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
        row.add(cell.toString().strip());
        cell.setLength(0);
        rows.add(row);
        row = new ArrayList<>();
        if (rows.size() > maxRows) throw new TooLong();
      } else {
        cell.append(c);
      }
    }
    if (cell.length() > 0 || !row.isEmpty()) {
      row.add(cell.toString().strip());
      rows.add(row);
    }
    return rows;
  }

  /** The file has more rows than an import takes. */
  static final class TooLong extends RuntimeException {
    TooLong() {
      super(null, null, false, false);
    }
  }

  static String decode(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException notUtf8) {
      return new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
    }
  }

  /** The separator used most on the first lines, outside quotes. */
  private static char separator(String text) {
    int comma = 0;
    int semi = 0;
    int tab = 0;
    int lines = 0;
    boolean quoted = false;
    for (int i = 0; i < text.length() && lines < 40; i++) {
      char c = text.charAt(i);
      if (c == '"') quoted = !quoted;
      else if (!quoted && c == ',') comma++;
      else if (!quoted && c == ';') semi++;
      else if (!quoted && c == '\t') tab++;
      else if (!quoted && c == '\n') lines++;
    }
    if (tab > comma && tab >= semi) return '\t';
    if (semi > comma) return ';';
    return ',';
  }
}

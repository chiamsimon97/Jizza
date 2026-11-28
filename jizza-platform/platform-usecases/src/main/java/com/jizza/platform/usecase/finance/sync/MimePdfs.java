package com.jizza.platform.usecase.finance.sync;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The PDF attachments of one email, from its raw MIME text (what Gmail's {@code get_thread} gives
 * with {@code messageFormat RAW}). Gmail's MCP server has no tool that downloads an attachment, so
 * Kai reads the parts itself: multipart bodies by their boundary, any depth, and base64 or
 * quoted-printable content. Only PDFs are kept, and nothing is ever logged.
 */
public final class MimePdfs {

  private MimePdfs() {}

  /** One attached PDF. */
  public record Pdf(String filename, byte[] bytes) {}

  /** A mailed statement is a few hundred KB; anything far bigger is not one. */
  static final int MAX_BYTES = 15 * 1024 * 1024;
  private static final int MAX_DEPTH = 6;
  private static final Pattern BOUNDARY = Pattern.compile("(?i)boundary\\s*=\\s*(\"([^\"]+)\"|([^;\\s]+))");
  private static final Pattern NAME = Pattern.compile("(?i)(?:file)?name\\*?\\s*=\\s*(\"([^\"]*)\"|([^;\\s]+))");
  private static final Pattern BASE64ISH = Pattern.compile("[A-Za-z0-9+/_=-]+");

  public static List<Pdf> extract(String raw) {
    List<Pdf> out = new ArrayList<>();
    if (raw == null || raw.isBlank()) return out;
    String message = raw;
    // Gmail's API returns the whole message base64url-encoded; some servers pass it on as is.
    String compact = raw.strip();
    if (!compact.contains("\n") && !compact.contains(" ") && BASE64ISH.matcher(compact).matches()) {
      try {
        message = new String(Base64.getUrlDecoder().decode(compact.replace('+', '-').replace('/', '_').replace("=", "")),
            StandardCharsets.ISO_8859_1);
      } catch (IllegalArgumentException notEncoded) {
        message = raw;
      }
    }
    walk(message.replace("\r\n", "\n"), out, 0);
    return out;
  }

  private static void walk(String part, List<Pdf> out, int depth) {
    if (depth > MAX_DEPTH) return;
    int split = part.indexOf("\n\n");
    String head = split < 0 ? part : part.substring(0, split);
    String body = split < 0 ? "" : part.substring(split + 2);
    Map<String, String> headers = headers(head);
    String type = headers.getOrDefault("content-type", "text/plain");
    String lower = type.toLowerCase(Locale.ROOT);
    if (lower.startsWith("multipart/")) {
      Matcher b = BOUNDARY.matcher(type);
      if (!b.find()) return;
      String boundary = "--" + (b.group(2) != null ? b.group(2) : b.group(3));
      String[] pieces = body.split(Pattern.quote(boundary));
      for (int i = 1; i < pieces.length; i++) {
        String piece = pieces[i];
        if (piece.startsWith("--")) break;
        walk(piece.startsWith("\n") ? piece.substring(1) : piece, out, depth + 1);
      }
      return;
    }
    String filename = filename(headers);
    boolean pdf = lower.startsWith("application/pdf")
        || (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf")
            && (lower.startsWith("application/octet-stream") || lower.startsWith("application/x-pdf")));
    if (!pdf) return;
    byte[] bytes = decode(body, headers.getOrDefault("content-transfer-encoding", "7bit"));
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) return;
    out.add(new Pdf(filename == null ? "statement.pdf" : filename, bytes));
  }

  /** Header names in lower case, folded lines joined. */
  private static Map<String, String> headers(String head) {
    Map<String, String> h = new LinkedHashMap<>();
    String name = null;
    StringBuilder value = new StringBuilder();
    for (String line : head.split("\n")) {
      if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
        value.append(' ').append(line.strip());
        continue;
      }
      if (name != null) h.putIfAbsent(name, value.toString().strip());
      int colon = line.indexOf(':');
      if (colon <= 0) {
        name = null;
        continue;
      }
      name = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
      value = new StringBuilder(line.substring(colon + 1));
    }
    if (name != null) h.putIfAbsent(name, value.toString().strip());
    return h;
  }

  private static String filename(Map<String, String> headers) {
    for (String key : List.of("content-disposition", "content-type")) {
      String v = headers.get(key);
      if (v == null) continue;
      Matcher m = NAME.matcher(v);
      if (m.find()) {
        String n = m.group(2) != null ? m.group(2) : m.group(3);
        // RFC 2231 form: utf-8''statement%20April.pdf
        int quote = n.indexOf("''");
        if (quote >= 0) n = java.net.URLDecoder.decode(n.substring(quote + 2), StandardCharsets.UTF_8);
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        return n.isEmpty() ? null : n.length() > 120 ? n.substring(n.length() - 120) : n;
      }
    }
    return null;
  }

  private static byte[] decode(String body, String encoding) {
    String enc = encoding.toLowerCase(Locale.ROOT).strip();
    try {
      if (enc.equals("base64")) return Base64.getMimeDecoder().decode(body.strip());
      if (enc.equals("quoted-printable")) return quotedPrintable(body);
      return body.getBytes(StandardCharsets.ISO_8859_1);
    } catch (IllegalArgumentException bad) {
      return null;
    }
  }

  private static byte[] quotedPrintable(String body) {
    var out = new java.io.ByteArrayOutputStream();
    String s = body.replace("=\n", "");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '=' && i + 2 < s.length()) {
        out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
        i += 2;
      } else {
        out.write((byte) c);
      }
    }
    return out.toByteArray();
  }
}

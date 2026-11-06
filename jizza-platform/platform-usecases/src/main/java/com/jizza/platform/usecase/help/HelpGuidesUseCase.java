package com.jizza.platform.usecase.help;

import com.jizza.engine.api.error.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * The Help guides, for the phone app. They are the same Markdown files the web app shows at
 * {@code /help} (jizza-client/src/help/guides), copied into the server at build time, and read with
 * the same header rules as the web's {@code guides.ts}: title, summary, section, order, updated.
 */
@Service
public class HelpGuidesUseCase {

  private static final Logger log = LoggerFactory.getLogger(HelpGuidesUseCase.class);
  private static final Pattern HEADER = Pattern.compile("\\A---\\n(.*?)\\n---\\n?", Pattern.DOTALL);
  private static final Pattern FIELD = Pattern.compile("^(\\w+):\\s*(.*)$");
  private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,80}");

  /** One guide as the Help list shows it. */
  public record Summary(String slug, String title, String summary, String section, int order,
                        String updated) {}

  /** One guide with its Markdown, header removed. */
  public record Guide(String slug, String title, String summary, String section, int order,
                      String updated, String body) {
    public Summary summaryOnly() {
      return new Summary(slug, title, summary, section, order, updated);
    }
  }

  private final List<Guide> guides;

  public HelpGuidesUseCase() {
    this(load("classpath*:help/guides/*.md"));
  }

  HelpGuidesUseCase(List<Guide> guides) {
    this.guides = guides.stream()
        .sorted(Comparator.comparing(Guide::section).thenComparingInt(Guide::order)
            .thenComparing(Guide::title))
        .toList();
  }

  public List<Summary> list() {
    return guides.stream().map(Guide::summaryOnly).toList();
  }

  public Guide get(String slug) {
    return guides.stream().filter(g -> g.slug().equals(slug)).findFirst()
        .orElseThrow(() -> new NotFoundException("Help guide", slug));
  }

  private static List<Guide> load(String pattern) {
    List<Guide> found = new ArrayList<>();
    try {
      for (Resource r : new PathMatchingResourcePatternResolver().getResources(pattern)) {
        String name = r.getFilename();
        if (name == null || !name.endsWith(".md")) continue;
        String slug = name.substring(0, name.length() - 3);
        if (!SLUG.matcher(slug).matches()) continue;
        try (InputStream in = r.getInputStream()) {
          found.add(parse(slug, new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
      }
    } catch (IOException e) {
      log.warn("Could not read the Help guides", e);
    }
    return found;
  }

  /** Reads one guide's header and body, as the web's {@code parseGuide} does. */
  static Guide parse(String slug, String raw) {
    String text = raw.replace("\r\n", "\n");
    Matcher header = HEADER.matcher(text);
    Map<String, String> fields = new LinkedHashMap<>();
    String body = text;
    if (header.find()) {
      for (String line : header.group(1).split("\n")) {
        Matcher m = FIELD.matcher(line);
        if (m.matches()) fields.put(m.group(1), m.group(2).strip());
      }
      body = text.substring(header.end());
    }
    int order;
    try {
      order = Integer.parseInt(fields.getOrDefault("order", "100"));
    } catch (NumberFormatException e) {
      order = 100;
    }
    String title = fields.getOrDefault("title", "");
    String section = fields.getOrDefault("section", "");
    String updated = fields.getOrDefault("updated", "");
    return new Guide(slug, title.isEmpty() ? slug : title, fields.getOrDefault("summary", ""),
        section.isEmpty() ? "General" : section, order, updated.isEmpty() ? null : updated, body);
  }
}

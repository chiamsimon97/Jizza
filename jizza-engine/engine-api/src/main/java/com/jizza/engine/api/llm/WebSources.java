package com.jizza.engine.api.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The web pages a reply drew on, read from the provider's own content blocks: the pages it cited
 * first, then pages it read whole, and only when it cited and read nothing, the top search results.
 * Shown as chips under the reply so the person can check where an answer came from.
 */
public final class WebSources {

  /** A page, by its address and the title the provider gave it. */
  public record Source(String url, String title) {}

  /** At most this many: past a handful, chips stop helping. */
  public static final int MAX = 8;
  private static final int SEARCH_FALLBACK = 5;

  private WebSources() {}

  public static List<Source> from(List<JsonNode> content) {
    Map<String, Source> cited = new LinkedHashMap<>();
    Map<String, Source> read = new LinkedHashMap<>();
    Map<String, Source> found = new LinkedHashMap<>();
    if (content == null) return List.of();
    for (JsonNode block : content) {
      switch (block.path("type").asText()) {
        case "text" -> {
          for (JsonNode citation : block.path("citations")) {
            add(cited, citation.path("url").asText(null), citation.path("title").asText(null));
          }
        }
        case "web_fetch_tool_result" -> {
          JsonNode result = block.path("content");
          add(read, result.path("url").asText(null),
              result.path("content").path("title").asText(null));
        }
        case "web_search_tool_result" -> {
          // An error comes back as an object rather than a list of results.
          if (!block.path("content").isArray()) break;
          for (JsonNode result : block.path("content")) {
            add(found, result.path("url").asText(null), result.path("title").asText(null));
          }
        }
        default -> { /* text without sources, thinking, tool calls */ }
      }
    }
    List<Source> out = new ArrayList<>(cited.values());
    read.forEach((url, source) -> { if (!cited.containsKey(url)) out.add(source); });
    if (out.isEmpty()) found.values().stream().limit(SEARCH_FALLBACK).forEach(out::add);
    return out.size() > MAX ? List.copyOf(out.subList(0, MAX)) : List.copyOf(out);
  }

  private static void add(Map<String, Source> into, String url, String title) {
    if (url == null || url.isBlank() || !web(url)) return;
    String name = title == null || title.isBlank() ? host(url) : title.strip();
    into.putIfAbsent(url, new Source(url, name.length() > 120 ? name.substring(0, 119) + "…" : name));
  }

  /** Only ordinary web addresses become links. */
  private static boolean web(String url) {
    try {
      String scheme = URI.create(url).getScheme();
      return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static String host(String url) {
    try {
      String host = URI.create(url).getHost();
      return host == null ? url : host.replaceFirst("^www\\.", "");
    } catch (IllegalArgumentException e) {
      return url;
    }
  }
}

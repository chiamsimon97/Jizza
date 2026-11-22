package com.jizza.engine.api.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Steps only the user can take, such as opening a sign-in page, shown on the reply as a card with
 * buttons ({@link UiAction#SHOW_STEPS}) instead of a raw link in the text.
 *
 * <p>A card comes from one of two places: an agent calling {@code app_show_steps}, or a tool result
 * that hands back a sign-in link (an MCP server's {@code login} tool returning an authorize URL).
 * The second needs no agent to remember anything, so a link the user must open always becomes a
 * button.
 *
 * <p>Payload: {@code {"title", "intro"?, "source": "agent"|"auto", "steps": [{"label",
 * "detail"?, "button"?: {"kind": "link", "label", "url"} | {"kind": "reply", "label", "text"}}]}}.
 * Only http(s) links are ever put on a button.
 */
public final class UserSteps {

  private UserSteps() {}

  public static final int MAX_STEPS = 8;
  /** Sign-in links taken from one tool result, at most. */
  static final int MAX_LINKS = 3;

  /** A link the user has to open to sign in, and the service it signs in to. */
  public record SignInLink(String url, String service) {}

  private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>()\\[\\]{}`]+");
  /** A tool whose job is signing in: login, authorize, connect, ... (MCP prefix removed). */
  private static final Pattern SIGN_IN_TOOL = Pattern.compile(
      "(^|[_\\-.])(log_?in|sign_?in|auth|authori[sz]e|authenticate|oauth|connect)([_\\-.]|$)");
  /** A page whose path says it signs in or asks for consent. */
  private static final Pattern SIGN_IN_PATH = Pattern.compile(
      "/(oauth2?|authori[sz]e|login|log-in|signin|sign-in|sso|consent)(/|$|\\?)",
      Pattern.CASE_INSENSITIVE);

  /** The sign-in links in a tool's result, or none when it gave no link the user must open. */
  public static List<SignInLink> signInLinks(String toolName, JsonNode output) {
    if (output == null || output.isNull() || output.isMissingNode()) return List.of();
    String bare = bareToolName(toolName);
    boolean signInTool = SIGN_IN_TOOL.matcher(bare.toLowerCase(Locale.ROOT)).find();
    Set<String> found = new LinkedHashSet<>();
    collectUrls(output, found);
    List<SignInLink> links = new ArrayList<>();
    for (String url : found) {
      if (links.size() == MAX_LINKS) break;
      URI uri = parse(url);
      if (uri == null) continue;
      String path = uri.getRawPath() == null ? "" : uri.getRawPath();
      if (signInTool || SIGN_IN_PATH.matcher(path + "/").find()) {
        links.add(new SignInLink(url, serviceName(toolName, uri)));
      }
    }
    return links;
  }

  /** The card for sign-in links a tool gave: open each page, then tell the agent to carry on. */
  public static UiAction signInCard(List<SignInLink> links) {
    String service = links.get(0).service();
    boolean same = links.stream().allMatch(l -> l.service().equals(service));
    String name = same ? service : "your accounts";
    ObjectNode payload = Json.obj().put("title", "Sign in to " + name).put("source", "auto");
    payload.put("intro", "Only you can do this part. Each button opens in a new tab.");
    ArrayNode steps = payload.putArray("steps");
    for (SignInLink link : links) {
      ObjectNode step = steps.addObject().put("label", "Log in to " + link.service())
          .put("detail", "Sign in on " + link.service() + "'s page and allow access.");
      step.putObject("button").put("kind", "link").put("label", "Log in to " + link.service())
          .put("url", link.url());
    }
    ObjectNode last = steps.addObject().put("label", "Come back here and continue");
    last.putObject("button").put("kind", "reply").put("label", "Done, continue")
        .put("text", "I've logged in to " + name + ". Please continue.");
    return new UiAction(UiAction.SHOW_STEPS, payload);
  }

  /** A connector to sign in to again, on a page of the app. */
  public record Reconnect(String name, String path) {}

  /**
   * A card for connectors that lost their sign-in while the reply ran: each one opens its page in
   * the app, where the person signs in again, then they ask Jizza to carry on.
   */
  public static UiAction reconnectCard(List<Reconnect> connectors) {
    String name = connectors.size() == 1 ? connectors.get(0).name() : "your connectors";
    ObjectNode payload = Json.obj().put("title", "Sign in to " + name + " again").put("source", "auto");
    payload.put("intro", "The sign-in expired or was refused, so Jizza couldn't use it. Only you can sign in.");
    ArrayNode steps = payload.putArray("steps");
    for (Reconnect c : connectors) {
      ObjectNode step = steps.addObject().put("label", "Sign in to " + c.name() + " again")
          .put("detail", "Open its page in Connectors and sign in.");
      step.putObject("button").put("kind", "page").put("label", "Open " + c.name())
          .put("path", c.path());
    }
    ObjectNode last = steps.addObject().put("label", "Come back here and continue");
    last.putObject("button").put("kind", "reply").put("label", "Done, continue")
        .put("text", "I've signed in to " + name + " again. Please continue.");
    return new UiAction(UiAction.SHOW_STEPS, payload);
  }

  /**
   * A card that sends the person to a service's Authentication tab to add its key or sign in, so a
   * credential never passes through the chat (K5).
   */
  public static UiAction credentialsCard(String service, String path, String what) {
    ObjectNode payload = Json.obj().put("title", "Connect " + service).put("source", "agent");
    payload.put("intro", "Only you can do this part. Keys stay on the Authentication tab and "
        + "never go into the chat.");
    ArrayNode steps = payload.putArray("steps");
    ObjectNode open = steps.addObject().put("label", "Add the sign-in for " + service);
    if (what != null && !what.isBlank()) open.put("detail", what.strip());
    open.putObject("button").put("kind", "page").put("label", "Open Authentication")
        .put("path", path);
    ObjectNode last = steps.addObject().put("label", "Come back here and continue");
    last.putObject("button").put("kind", "reply").put("label", "Done, continue")
        .put("text", "I've added the sign-in for " + service + ". Please continue.");
    return new UiAction(UiAction.SHOW_STEPS, payload);
  }

  /** The links already on a card, so a link is never shown on two cards. */
  public static Set<String> urls(UiAction action) {
    Set<String> urls = new LinkedHashSet<>();
    if (action == null || !UiAction.SHOW_STEPS.equals(action.type())) return urls;
    for (JsonNode step : action.payload().path("steps")) {
      String url = step.path("button").path("url").asText("");
      if (!url.isEmpty()) urls.add(url);
    }
    return urls;
  }

  /** True for an absolute http(s) link: the only kind a button may open. */
  public static boolean safeUrl(String url) {
    URI uri = parse(url);
    return uri != null && uri.getHost() != null && !uri.getHost().isBlank();
  }

  private static URI parse(String url) {
    if (url == null || url.length() > 4000) return null;
    try {
      URI uri = new URI(url);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      return scheme.equals("https") || scheme.equals("http") ? uri : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static void collectUrls(JsonNode node, Set<String> into) {
    if (node.isTextual()) {
      Matcher m = URL.matcher(node.asText());
      while (m.find()) into.add(trimTrailing(m.group()));
    } else if (node.isContainerNode()) {
      node.forEach(child -> collectUrls(child, into));
    }
  }

  /** A sentence's full stop or comma is not part of the link. */
  private static String trimTrailing(String url) {
    int end = url.length();
    while (end > 0 && ".,;:!?*_".indexOf(url.charAt(end - 1)) >= 0) end--;
    return url.substring(0, end);
  }

  /** {@code mcp_kite__login} is the {@code login} tool of the "kite" server. */
  private static String bareToolName(String toolName) {
    if (toolName == null) return "";
    int split = toolName.indexOf("__");
    return toolName.startsWith("mcp_") && split > 0 ? toolName.substring(split + 2) : toolName;
  }

  /** "Kite" for the kite server's tool, otherwise the name in the link's host. */
  static String serviceName(String toolName, URI uri) {
    if (toolName != null && toolName.startsWith("mcp_")) {
      int split = toolName.indexOf("__");
      String slug = split > 4 ? toolName.substring(4, split) : "";
      if (!slug.isBlank()) return titled(slug);
    }
    String[] parts = uri.getHost().split("\\.");
    String name = parts.length >= 2 ? parts[parts.length - 2] : parts[0];
    return titled(name);
  }

  private static String titled(String slug) {
    String words = slug.replaceAll("[_\\-]+", " ").strip();
    if (words.isEmpty()) return "the service";
    return Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }
}

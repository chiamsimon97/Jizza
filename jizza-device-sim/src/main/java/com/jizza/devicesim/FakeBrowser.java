package com.jizza.devicesim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small scripted browser for server tests, so web workflows exercise the real device protocol
 * without launching Chrome. Pages contain only the element facts browser actions need.
 */
public final class FakeBrowser {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final SimDevice device;
  private final Map<String, Page> pages = new LinkedHashMap<>();
  private String url = "about:blank";
  private String session;
  private Element failedCandidate;
  private final List<ObjectNode> scriptedSteps = new ArrayList<>();

  public FakeBrowser(SimDevice device) {
    this.device = device;
    registerHandlers();
    ObjectNode limits = device.json().createObjectNode();
    limits.put("family", "chrome").put("version", "131.0.6778.86")
        .put("trustedInput", true).put("sessions", 3);
    limits.putArray("allowedSites").add("*");
    device.capability("browser", limits);
  }

  /** Adds or returns a page in the scripted model. */
  public Page page(String pageUrl) {
    return pages.computeIfAbsent(pageUrl, Page::new);
  }

  /** Makes the next element lookup fail with one suggested locator, which is never acted on. */
  public FakeBrowser failNextLookup(Element candidate) {
    this.failedCandidate = candidate;
    return this;
  }

  private void registerHandlers() {
    handle("browser.open@2", this::open);
    handle("browser.click@2", in -> elementResult(in, "clicked", true).put("navigated", false).put("url", url));
    handle("browser.hover@1", in -> elementResult(in, "hovered", true));
    handle("browser.setText@1", this::setText);
    handle("browser.getText@1", this::getText);
    handle("browser.setCheckbox@1", this::setCheckbox);
    handle("browser.getCheckbox@1", in -> object().put("checked", find(in).checked));
    handle("browser.selectItem@1", this::selectItem);
    handle("browser.extractTable@1", this::extractTable);
    handle("browser.selectRadio@1", this::selectRadio);
    handle("browser.getRadio@1", this::getRadio);
    handle("browser.scroll@1", in -> object().put("scrollX", 0).put("scrollY", 100).put("atEnd", false));
    handle("browser.sendKeys@1", in -> object().put("sent", true));
    handle("browser.typeInto@1", in -> {
      Element element = find(in);
      if (in.has("text")) element.value = in.path("text").asText();
      return object().put("typed", true);
    });
    handle("browser.exists@1", this::exists);
    handle("browser.switchTo@1", in -> pageDetails());
    handle("browser.refresh@1", in -> pageDetails());
    handle("browser.getSelectors@1", this::selectors);
    handle("browser.pageInfo@1", in -> pageDetails().put("readyState", "complete")
        .put("lang", "en").put("scrollY", 0));
    handle("browser.forward@1", in -> pageDetails().put("moved", false));
    handle("browser.back@1", in -> pageDetails().put("moved", false));
    handle("browser.close@1", in -> {
      session = null;
      return object().put("closed", true);
    });
    handle("browser.wait@1", in -> object().put("waitedMs", in.path("ms").asInt(0)));
    handle("browser.screenshot@1", in -> object().put("file", "fake-screenshot"));
    handle("browser.download@1", in -> object().put("file", "fake-download")
        .put("name", "download.bin").put("size", 0));
    handle("browser.pickElement@1", this::pickElement);
    handle("browser.record@1", this::record);
  }

  private void handle(String action, java.util.function.Function<JsonNode, JsonNode> handler) {
    device.handle(action, input -> {
      boolean opensItself = "browser.open@2".equals(action)
          || "browser.record@1".equals(action)
          || ("browser.pickElement@1".equals(action) && input.hasNonNull("url"));
      if (!opensItself) requireSession(input);
      return handler.apply(input);
    });
  }

  private void requireSession(JsonNode input) {
    String requested = input.path("session").asText(null);
    if (session != null && (requested == null || session.equals(requested))) return;
    ObjectNode output = object();
    output.set("error", object().put("kind", "no_session")
        .put("detail", requested == null ? "There is no live browser session"
            : "The requested browser session is not live"));
    throw new SimDevice.Failure("failed", "There is no live browser session", output);
  }

  private JsonNode open(JsonNode in) {
    url = in.path("url").asText(url);
    page(url);
    if (session == null || "newSession".equals(in.path("in").asText())) session = sessionId();
    return pageDetails().put("sessionId", session).put("tabId", "tab-1");
  }

  private JsonNode setText(JsonNode in) {
    Element element = find(in);
    if (in.has("text")) element.value = in.path("text").asText();
    ObjectNode out = object().put("set", true);
    if (!in.has("secretRef") && !"password".equals(element.role)) out.put("value", element.value);
    return out;
  }

  private JsonNode getText(JsonNode in) {
    Element element = find(in);
    String what = in.path("what").asText("text");
    return object().put("text", "value".equals(what) ? element.value : element.text);
  }

  private JsonNode setCheckbox(JsonNode in) {
    Element element = find(in);
    boolean before = element.checked;
    element.checked = switch (in.path("state").asText()) {
      case "checked" -> true;
      case "unchecked" -> false;
      default -> !element.checked;
    };
    return object().put("checked", element.checked).put("changed", before != element.checked);
  }

  private JsonNode selectItem(JsonNode in) {
    Element element = find(in);
    String value = in.path("items").path(0).asText();
    element.value = value;
    ObjectNode out = object();
    out.putArray("selected").add(object().put("value", value).put("label", value));
    return out;
  }

  private JsonNode extractTable(JsonNode in) {
    Element table = find(in);
    ObjectNode out = object();
    ArrayNode columns = out.putArray("columns");
    if (!table.rows.isEmpty()) table.rows.get(0).keySet().forEach(columns::add);
    ArrayNode rows = out.putArray("rows");
    table.rows.forEach(row -> rows.add(device.json().valueToTree(row)));
    return out.put("rowCount", table.rows.size()).put("pages", 1);
  }

  private JsonNode selectRadio(JsonNode in) {
    Element element = find(in);
    element.checked = true;
    return object().set("selected", object().put("value", element.value).put("label", element.label));
  }

  private JsonNode getRadio(JsonNode in) {
    Element element = find(in);
    return object().put("checked", element.checked);
  }

  private JsonNode exists(JsonNode in) {
    try {
      find(in);
      return object().put("exists", true).put("count", 1).put("visible", true);
    } catch (SimDevice.Failure ignored) {
      return object().put("exists", false).put("count", 0).put("visible", false);
    }
  }

  private JsonNode selectors(JsonNode in) {
    Element element = in.has("element") ? find(in) : firstElement();
    ObjectNode bundle = bundle(element);
    ObjectNode out = object();
    out.set("element", bundle);
    return out.put("css", "[aria-label=\"" + element.name + "\"]").put("xpath", "//*");
  }

  /**
   * Scripts one step the person "does" while a recording runs. A sensitive step is sent without
   * its value, as the real extension does.
   */
  public FakeBrowser recordedStep(String action, int version, String label, ObjectNode input,
                                  boolean sensitive) {
    ObjectNode step = object().put("seq", scriptedSteps.size()).put("action", action)
        .put("version", version).put("label", label);
    step.set("input", input);
    ObjectNode meta = step.putObject("meta");
    meta.put("url", url).put("sensitive", sensitive);
    if (sensitive) step.putNull("secretRef");
    scriptedSteps.add(step);
    return this;
  }

  private JsonNode record(JsonNode in) {
    String recordingId = in.path("recordingId").asText();
    for (ObjectNode step : scriptedSteps) {
      ObjectNode data = object().put("recordingId", recordingId);
      data.set("step", step);
      device.event("browser.recording.step", data);
    }
    device.event("browser.recording.stopped", object().put("recordingId", recordingId)
        .put("stepCount", scriptedSteps.size()).put("stoppedBy", "person"));
    return object().put("stepCount", scriptedSteps.size()).put("stoppedBy", "person");
  }

  private JsonNode pickElement(JsonNode in) {
    if (in.hasNonNull("url")) {
      url = in.path("url").asText();
      page(url);
      if (session == null) session = sessionId();
    }
    Element element = firstElement();
    ObjectNode out = object();
    out.set("element", bundle(element));
    return out.put("preview", "fake-preview").put("url", url);
  }

  private ObjectNode elementResult(JsonNode in, String field, boolean value) {
    find(in);
    return object().put(field, value);
  }

  private Element find(JsonNode in) {
    if (failedCandidate != null) {
      Element candidate = failedCandidate;
      failedCandidate = null;
      ObjectNode error = object();
      ArrayNode candidates = error.putObject("error").put("kind", "element_not_found")
          .put("detail", "No scripted element matched").put("url", url).putArray("candidates");
      ObjectNode suggestion = object();
      suggestion.set("element", bundle(candidate));
      candidates.add(suggestion.put("score", 0.9).put("text", candidate.text));
      throw new SimDevice.Failure("failed", "Could not find " + candidate.label, error);
    }
    JsonNode strategies = in.path("element").path("strategies");
    for (JsonNode strategy : strategies) {
      for (Element element : page(url).elements) {
        String by = strategy.path("by").asText();
        if ("role".equals(by) && element.role.equals(strategy.path("role").asText())
            && element.name.equals(strategy.path("name").asText())) return element;
        if ("label".equals(by) && element.label.equals(strategy.path("value").asText())) return element;
        if ("text".equals(by) && element.text.equals(strategy.path("value").asText())) return element;
      }
    }
    throw new SimDevice.Failure("failed", "Could not find the scripted element",
        object().set("error", object().put("kind", "element_not_found")
            .put("detail", "No scripted element matched").put("url", url)));
  }

  private Element firstElement() {
    if (!page(url).elements.isEmpty()) return page(url).elements.get(0);
    throw new SimDevice.Failure("failed", "The scripted page has no elements");
  }

  private ObjectNode bundle(Element element) {
    ObjectNode bundle = object().put("label", element.label);
    bundle.putArray("strategies").add(object().put("by", "role")
        .put("role", element.role).put("name", element.name));
    bundle.putObject("fingerprint").put("tag", element.role).put("text", element.text);
    return bundle;
  }

  private ObjectNode pageDetails() {
    return object().put("tabId", "tab-1").put("url", url).put("title", page(url).title);
  }

  private ObjectNode object() {
    return device.json().createObjectNode();
  }

  private static String sessionId() {
    final char[] alphabet = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    StringBuilder id = new StringBuilder("s_");
    for (int i = 0; i < 8; i++) id.append(alphabet[RANDOM.nextInt(alphabet.length)]);
    return id.toString();
  }

  /** One URL in the fake browser, with elements kept in insertion order. */
  public static final class Page {
    private final List<Element> elements = new ArrayList<>();
    private String title;

    private Page(String url) {
      this.title = url;
    }

    public Page title(String value) {
      this.title = value;
      return this;
    }

    public Element element(String label, String role, String name) {
      Element element = new Element(label, role, name);
      elements.add(element);
      return element;
    }
  }

  /** The small set of element facts used by contract-shaped fake action outputs. */
  public static final class Element {
    private final String label;
    private final String role;
    private final String name;
    private String text = "";
    private String value = "";
    private boolean checked;
    private final List<Map<String, Object>> rows = new ArrayList<>();

    public Element(String label, String role, String name) {
      this.label = label;
      this.role = role;
      this.name = name;
    }

    public Element text(String text) { this.text = text; return this; }
    public Element value(String value) { this.value = value; return this; }
    public Element checked(boolean checked) { this.checked = checked; return this; }
    public Element row(Map<String, Object> row) { this.rows.add(Map.copyOf(row)); return this; }
  }
}

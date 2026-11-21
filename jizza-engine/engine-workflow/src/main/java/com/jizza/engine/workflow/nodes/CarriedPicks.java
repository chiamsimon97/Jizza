package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.workflow.engine.RunContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Choices from a list carried from one step of a run to the next.
 *
 * <p>A step that chooses inside a card of another list (a date inside a train) names that card by a
 * mapped value ({@code {{ input.trains }}}) or by the list's name ("Trains"). When the value is empty
 * and an earlier step of the same run already chose a card of that list (the train chosen with its
 * coach), the later step goes on with that card instead of asking for it again. The approval card
 * shows it, and the choices the list follows ({@code config.list.dependsOn}: the coach the dates
 * load after), as fixed values chosen earlier, which the person reads but does not change there.
 */
final class CarriedPicks {

  /** One value chosen earlier, as the approval card lists it; {@code key} is the field it fills. */
  record Carried(String key, String label, String value) {
    ObjectNode toJson() {
      ObjectNode out = Json.obj().put("label", label).put("value", value);
      if (key != null) out.put("key", key);
      return out;
    }
  }

  static final String STATE = "carried";

  private CarriedPicks() {}

  /** A mapped value as a key: {@code {{ input.trains }}} and {@code {{input.trains}}} match. */
  static String sourceKey(JsonNode configured) {
    if (configured == null || !configured.isTextual()) return null;
    String text = configured.asText();
    if (!text.contains("{{")) return null;
    String key = text.replaceAll("\\s+", "");
    return key.isEmpty() ? null : key;
  }

  static String listKey(String title) {
    if (title == null) return null;
    String folded = ItemMatch.fold(title);
    return folded.isEmpty() ? null : folded;
  }

  /** The latest pick of another step that chose from the list named, or for the value mapped. */
  static RunContext.Pick find(List<RunContext.Pick> picks, String nodeId, String source,
                              String list) {
    String listKey = listKey(list);
    RunContext.Pick found = null;
    for (RunContext.Pick pick : picks) {
      if (pick.nodeId().equals(nodeId) || pick.value() == null || pick.value().isBlank()) continue;
      boolean sameSource = source != null && source.equals(sourceKey(text(pick.source())));
      boolean sameList = listKey != null && listKey.equals(listKey(pick.list()));
      if (sameSource || sameList) found = pick;
    }
    return found;
  }

  /**
   * Fills the card a step chooses inside from an earlier step, when the step's own value for it is
   * empty, and lists what was carried (and what the list follows) in the step's state for the card.
   * Returns whether the card was carried.
   */
  static boolean carry(RunContext context, String nodeId, JsonNode config, ObjectNode inputs,
                       ObjectNode state) {
    return carry(context, nodeId, config, inputs, state, id -> null);
  }

  /** As above; {@code configOf} gives another step's settings, to follow a chain of lists. */
  static boolean carry(RunContext context, String nodeId, JsonNode config, ObjectNode inputs,
                       ObjectNode state, java.util.function.Function<String, JsonNode> configOf) {
    List<RunContext.Pick> picks = context.picks();
    List<Carried> carried = new ArrayList<>();
    boolean carriedCard = false;
    JsonNode list = config.path("list");
    JsonNode within = inputs.path("within");
    if (within.isObject() && WebStepForm.empty(within.get("item"))) {
      JsonNode configured = config.path("inputs").path("within").get("item");
      boolean mapped = configured == null || WebStepForm.empty(configured)
          || sourceKey(configured) != null;
      String title = list.path("withinTitle").asText(null);
      RunContext.Pick pick = mapped ? find(picks, nodeId, sourceKey(configured), title) : null;
      if (pick != null) {
        ((ObjectNode) within).put("item", pick.value());
        carried.add(new Carried("within.item", label(title, pick, "Card"), pick.value()));
        // What was chosen inside that same card before (the coach of the train), for context.
        carried.addAll(chosenInside(picks, nodeId, pick));
        carriedCard = true;
      }
    }
    // The list this one loads after (the coach the dates follow), and the lists that one follows
    // in turn (the train the coach is in): read on the card in that order, never sent.
    carried.addAll(followed(picks, nodeId, list.path("dependsOn"), configOf));
    List<Carried> unique = dedupe(carried);
    if (unique.isEmpty()) {
      state.remove(STATE);
    } else {
      ArrayNode out = state.putArray(STATE);
      unique.forEach(c -> out.add(c.toJson()));
    }
    return carriedCard;
  }

  /**
   * What the card shows as chosen earlier: the values carried; none when the card carried is asked
   * for again (the page no longer lists it, so it is chosen again from what it lists now).
   */
  static ArrayNode shown(ObjectNode state, List<WebStepForm.Field> form) {
    JsonNode carried = state.get(STATE);
    if (carried == null || !carried.isArray() || carried.isEmpty()) return null;
    Set<String> asked = new java.util.HashSet<>();
    form.forEach(f -> asked.add(f.key()));
    ArrayNode out = Json.mapper().createArrayNode();
    for (JsonNode c : carried) {
      // The card carried is not on the page now: it is asked again, and what was chosen with it
      // no longer holds.
      if (c.hasNonNull("key") && asked.contains(c.get("key").asText())) return null;
    }
    for (JsonNode c : carried) {
      out.add(Json.obj().put("label", c.path("label").asText())
          .put("value", c.path("value").asText()));
    }
    return out.isEmpty() ? null : out;
  }

  /**
   * What a list step chose, kept for later steps: the card it chose inside, and the item. Called
   * when the step sends its click, with the names the page showed for them.
   */
  static List<RunContext.Pick> picksOf(String nodeId, JsonNode config, JsonNode inputs,
                                       String card, String item) {
    List<RunContext.Pick> out = new ArrayList<>();
    JsonNode list = config.path("list");
    JsonNode configured = config.path("inputs");
    if (inputs.path("within").isObject() && card != null && !card.isBlank()) {
      out.add(new RunContext.Pick(nodeId, list.path("withinTitle").asText(null),
          sourceKey(configured.path("within").get("item")), card));
    }
    if (item != null && !item.isBlank()) {
      out.add(new RunContext.Pick(nodeId, list.path("title").asText(null),
          sourceKey(configured.get("item")), item));
    }
    return out;
  }

  /**
   * The chain of choices a list loads after, outermost first: the coach the dates follow is
   * preceded by the train it was chosen in, and by whatever list the coach's own step loads after.
   */
  private static List<Carried> followed(List<RunContext.Pick> picks, String nodeId,
                                        JsonNode follows,
                                        java.util.function.Function<String, JsonNode> configOf) {
    List<List<Carried>> hops = new ArrayList<>();
    Set<String> seen = new java.util.HashSet<>();
    seen.add(nodeId);
    for (int depth = 0; depth < 6 && follows.isObject()
        && !"inside".equals(follows.path("relation").asText("after")); depth++) {
      String title = follows.path("title").asText(null);
      String input = follows.path("inputName").asText("").strip();
      String source = input.isEmpty() ? null : "{{input." + input + "}}";
      RunContext.Pick pick = find(picks, nodeId, source, title);
      if (pick == null || !seen.add(pick.nodeId())) break;
      List<Carried> hop = new ArrayList<>();
      // The card that choice was made in (the train of the coach), then the choice itself.
      for (RunContext.Pick other : picks) {
        if (other.nodeId().equals(pick.nodeId()) && other != pick && named(other)) {
          hop.add(new Carried(null, other.list().strip(), other.value()));
        }
      }
      hop.add(new Carried(null, label(title, pick, "Chosen earlier"), pick.value()));
      hops.add(0, hop);
      JsonNode next = configOf.apply(pick.nodeId());
      follows = next == null ? Json.obj() : next.path("list").path("dependsOn");
    }
    List<Carried> out = new ArrayList<>();
    hops.forEach(out::addAll);
    return out;
  }

  /**
   * The other choices made inside the card picked (the coach and the date chosen in that train):
   * the picks of every earlier step that chose inside, or chose, that same card.
   */
  private static List<Carried> chosenInside(List<RunContext.Pick> picks, String nodeId,
                                            RunContext.Pick card) {
    Set<String> steps = new LinkedHashSet<>();
    for (RunContext.Pick pick : picks) {
      if (pick.nodeId().equals(nodeId)) continue;
      boolean sameCard = pick.value().equals(card.value())
          && (same(pick.list(), card.list()) || card.source() != null
              && card.source().equals(pick.source()));
      if (sameCard) steps.add(pick.nodeId());
    }
    List<Carried> out = new ArrayList<>();
    for (RunContext.Pick pick : picks) {
      if (!steps.contains(pick.nodeId()) || !named(pick)) continue;
      if (pick.value().equals(card.value()) && same(pick.list(), card.list())) continue;
      out.add(new Carried(null, pick.list().strip(), pick.value()));
    }
    return out;
  }

  private static boolean named(RunContext.Pick pick) {
    return pick.list() != null && !pick.list().isBlank();
  }

  private static boolean same(String a, String b) {
    String ka = listKey(a);
    return ka != null && ka.equals(listKey(b));
  }

  private static String label(String title, RunContext.Pick pick, String fallback) {
    if (title != null && !title.isBlank()) return title.strip();
    if (pick.list() != null && !pick.list().isBlank()) return pick.list().strip();
    return fallback;
  }

  /** One entry per list, in the order first met, with the latest value (and its field, if any). */
  private static List<Carried> dedupe(List<Carried> carried) {
    java.util.LinkedHashMap<String, Carried> byList = new java.util.LinkedHashMap<>();
    for (Carried c : carried) {
      String key = ItemMatch.fold(c.label());
      Carried was = byList.get(key);
      byList.put(key, was != null && was.key() != null ? was : c);
    }
    return new ArrayList<>(byList.values());
  }

  private static JsonNode text(String value) {
    return value == null ? null : Json.mapper().getNodeFactory().textNode(value);
  }
}

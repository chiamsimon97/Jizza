package com.jizza.engine.memory.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.MemoryStatus;
import com.jizza.engine.api.knowledge.KnowledgeEntry;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.MemoryEntryEntity;
import com.jizza.engine.persistence.support.MemoryReviewQueries.FailedStep;
import com.jizza.engine.persistence.support.MemoryReviewQueries.FailedToolCall;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Turns what the model suggested into suggestions the user can act on, keeping only what holds up.
 *
 * <p>The model is a reader, not an authority. Every fact, agent and piece of evidence must be one
 * of the dossier's references; a change to a fact needs the user's own words as evidence (a fact
 * is never retired or reworded on the strength of a failed tool call or a web page); a lesson
 * needs a failure as evidence and is about a tool, site or workflow the review actually saw; and
 * how often something happened is counted here from the evidence, never taken from the model.
 */
final class ProposalReader {

  static final String MERGE = "merge";
  static final String CONFIRM = "confirm";
  static final String RETIRE = "retire";
  static final String UPDATE = "update";
  static final String LESSON = "lesson";

  static final int MAX_LESSON_CHARS = 600;
  private static final int MAX_REASON_CHARS = 300;

  /**
   * A suggestion ready to store.
   *
   * @param payload  what approving does: {@code facts} (ids), {@code keep}, {@code content},
   *                 {@code factHashes} (to notice a fact changed since), {@code agentId},
   *                 {@code aboutKey}, {@code aboutLabel}
   * @param evidence {@code [{kind, label, detail, link, at, ids}]}
   */
  record Drafted(String kind, String summary, String reason, ObjectNode payload, ArrayNode evidence,
                 int occurrences, String fingerprint) {}

  private ProposalReader() {}

  static List<Drafted> read(JsonNode reply, ReviewDossier dossier, int maxFactChars, int limit) {
    List<Drafted> out = new ArrayList<>();
    if (reply == null) return out;
    Set<String> fingerprints = new LinkedHashSet<>();
    for (JsonNode p : reply.path("proposals")) {
      if (out.size() >= limit) break;
      Drafted d = switch (p.path("kind").asText("").strip().toLowerCase(Locale.ROOT)) {
        case MERGE -> merge(p, dossier, maxFactChars);
        case CONFIRM -> single(CONFIRM, p, dossier);
        case RETIRE -> single(RETIRE, p, dossier);
        case UPDATE -> update(p, dossier, maxFactChars);
        case LESSON -> lesson(p, dossier);
        default -> null;
      };
      if (d != null && fingerprints.add(d.fingerprint())) out.add(d);
    }
    return out;
  }

  // ── facts ───────────────────────────────────────────────────────────────────────────────────

  private static Drafted merge(JsonNode p, ReviewDossier d, int maxFactChars) {
    List<MemoryEntryEntity> facts = facts(p, d);
    if (facts.size() < 2) return null;
    String content = content(p, maxFactChars);
    if (content == null) return null;
    Evidence ev = evidence(p, d);
    // The fact the user stands behind stays; the others are retired into it.
    MemoryEntryEntity keep = facts.stream().filter(MemoryEntryEntity::isConfirmed).findFirst()
        .orElse(facts.getFirst());
    ObjectNode payload = factPayload(facts).put("keep", keep.getId().toString()).put("content", content);
    String summary = "Merge " + facts.size() + " memories that say the same thing into one: “"
        + content + "”";
    return drafted(MERGE, summary, p, payload, ev, Math.max(1, ev.chats()), content, facts, null);
  }

  private static Drafted single(String kind, JsonNode p, ReviewDossier d) {
    List<MemoryEntryEntity> facts = facts(p, d);
    if (facts.size() != 1) return null;
    MemoryEntryEntity fact = facts.getFirst();
    Evidence ev = evidence(p, d);
    // Only what the user said can confirm or retire something they told Jizza.
    if (ev.chats() == 0) return null;
    if (CONFIRM.equals(kind) && fact.isConfirmed() && fact.getStatus() == MemoryStatus.ACTIVE) return null;
    String summary = CONFIRM.equals(kind)
        ? "Confirm: “" + fact.getContent() + "”"
        : "Retire, as it is no longer true: “" + fact.getContent() + "”";
    return drafted(kind, summary, p, factPayload(facts), ev, ev.chats(), null, facts, null);
  }

  private static Drafted update(JsonNode p, ReviewDossier d, int maxFactChars) {
    List<MemoryEntryEntity> facts = facts(p, d);
    if (facts.size() != 1) return null;
    MemoryEntryEntity fact = facts.getFirst();
    String content = content(p, maxFactChars);
    if (content == null || content.equalsIgnoreCase(fact.getContent())) return null;
    Evidence ev = evidence(p, d);
    if (ev.chats() == 0) return null;
    ObjectNode payload = factPayload(facts).put("content", content);
    String summary = "Change “" + fact.getContent() + "” to “" + content + "”";
    return drafted(UPDATE, summary, p, payload, ev, ev.chats(), content, facts, null);
  }

  // ── lessons ─────────────────────────────────────────────────────────────────────────────────

  private static Drafted lesson(JsonNode p, ReviewDossier d) {
    String content = content(p, MAX_LESSON_CHARS);
    if (content == null) return null;
    Evidence ev = evidence(p, d);
    // A lesson is learned from something that went wrong.
    if (ev.tools().isEmpty() && ev.steps().isEmpty()) return null;

    // What it is about: one of the failures it was learned from, or a site they mention.
    JsonNode about = p.path("about");
    String aboutKind = about.path("kind").asText("").strip().toLowerCase(Locale.ROOT);
    String aboutRef = about.path("ref").asText("").strip();
    String aboutKey;
    String label = ReviewDossier.clip(about.path("label").asText(""), 120);
    switch (aboutKind) {
      case "tool" -> {
        ReviewDossier.ToolFailures t = d.tools.get(aboutRef);
        if (t == null && !ev.tools().isEmpty()) t = ev.tools().getFirst();
        if (t == null) return null;
        aboutKey = "tool:" + t.tool();
        if (label == null || label.isBlank()) label = t.label() != null ? t.label() : t.tool();
      }
      case "site" -> {
        String host = siteOf(aboutRef, d, ev);
        if (host == null) return null;
        aboutKey = "site:" + host;
        if (label == null || label.isBlank()) label = host;
      }
      case "workflow" -> {
        ReviewDossier.StepFailures s = d.steps.get(aboutRef);
        if (s == null && !ev.steps().isEmpty()) s = ev.steps().getFirst();
        if (s == null) return null;
        aboutKey = "workflow:" + s.workflowId();
        if (label == null || label.isBlank()) label = s.workflowName();
      }
      default -> {
        aboutKey = "general";
        if (label != null && label.isBlank()) label = null;
      }
    }

    // Who learns it: the agent named, else the one that hit the failure, else the main assistant.
    ReviewDossier.Agent agent = d.agents.get(p.path("agent").asText("").strip());
    if (agent == null && !ev.tools().isEmpty()) {
      String ref = d.agentRef(ev.tools().getFirst().agentId());
      agent = ref == null ? null : d.agents.get(ref);
    }
    if (agent == null) agent = d.orchestrator();
    if (agent == null) return null;
    AgentEntity a = agent.entity();
    for (KnowledgeEntry known : d.lessons.getOrDefault(a.getId(), List.of())) {
      if (aboutKey.equals(known.lesson().aboutKey()) && content.equalsIgnoreCase(known.content())) {
        return null;
      }
    }

    int occurrences = ev.tools().stream().mapToInt(t -> t.calls().size()).sum()
        + ev.steps().stream().mapToInt(s -> s.steps().size()).sum();
    ObjectNode payload = Json.obj().put("agentId", a.getId().toString()).put("agentName", a.getName())
        .put("aboutKey", aboutKey).put("content", content);
    if (label != null) payload.put("aboutLabel", label);
    String summary = "Teach " + a.getName() + (label == null ? "" : " about " + label) + ": “"
        + content + "”";
    return drafted(LESSON, summary, p, payload, ev, occurrences, content, List.of(),
        a.getId() + "|" + aboutKey);
  }

  /** A site the review saw: named outright, or the site of a failure the lesson is learned from. */
  private static String siteOf(String ref, ReviewDossier d, Evidence ev) {
    ReviewDossier.ToolFailures t = d.tools.get(ref);
    if (t != null && t.host() != null) return t.host();
    ReviewDossier.StepFailures s = d.steps.get(ref);
    if (s != null && s.host() != null) return s.host();
    String host = ReviewDossier.normaliseHost(ref);
    if (host != null && d.mentionsHost(host)) return host;
    for (ReviewDossier.ToolFailures x : ev.tools()) if (x.host() != null) return x.host();
    for (ReviewDossier.StepFailures x : ev.steps()) if (x.host() != null) return x.host();
    return null;
  }

  // ── shared ──────────────────────────────────────────────────────────────────────────────────

  record Evidence(ArrayNode items, int chats, List<ReviewDossier.ToolFailures> tools,
                  List<ReviewDossier.StepFailures> steps) {}

  /** The evidence references that are real, as the user will see them. */
  static Evidence evidence(JsonNode p, ReviewDossier d) {
    ArrayNode items = Json.mapper().createArrayNode();
    int chats = 0;
    List<ReviewDossier.ToolFailures> tools = new ArrayList<>();
    List<ReviewDossier.StepFailures> steps = new ArrayList<>();
    Set<String> used = new LinkedHashSet<>();
    for (JsonNode refNode : p.path("evidence")) {
      String ref = refNode.asText("").strip();
      if (!used.add(ref)) continue;
      ReviewDossier.Message m = d.messages.get(ref);
      if (m != null) {
        chats++;
        ObjectNode ids = Json.obj().put("messageId", m.message().messageId().toString());
        if (m.message().conversationId() != null) {
          ids.put("conversationId", m.message().conversationId().toString());
        }
        items.addObject().put("kind", "chat").put("label", "You said: “" + m.message().content() + "”")
            .put("link", m.message().conversationId() == null ? null
                : "/chat?conversationId=" + m.message().conversationId())
            .put("at", String.valueOf(m.message().at())).set("ids", ids);
        continue;
      }
      ReviewDossier.ToolFailures t = d.tools.get(ref);
      if (t != null) {
        tools.add(t);
        FailedToolCall last = t.calls().getLast();
        ArrayNode jobs = Json.mapper().createArrayNode();
        t.calls().stream().map(FailedToolCall::jobId).distinct().forEach(id -> jobs.add(id.toString()));
        String who = t.agentName() == null ? "An agent" : t.agentName();
        String what = t.label() != null ? t.label() : t.tool();
        items.addObject().put("kind", "tool")
            .put("label", who + "’s call to " + what + " failed " + times(t.calls().size()))
            .put("detail", t.error())
            .put("link", last.conversationId() == null ? null : "/chat?conversationId=" + last.conversationId())
            .put("at", String.valueOf(last.at()))
            .set("ids", Json.obj().put("tool", t.tool()).set("jobIds", jobs));
        continue;
      }
      ReviewDossier.StepFailures s = d.steps.get(ref);
      if (s != null) {
        steps.add(s);
        FailedStep last = s.steps().getLast();
        ArrayNode runs = Json.mapper().createArrayNode();
        s.steps().stream().map(FailedStep::runId).distinct().forEach(id -> runs.add(id.toString()));
        String step = s.nodeLabel() == null ? "A step" : "Step “" + s.nodeLabel() + "”";
        items.addObject().put("kind", "step")
            .put("label", step + " in " + quoted(s.workflowName()) + " failed " + times(s.steps().size()))
            .put("detail", s.error())
            .put("link", "/workflows?open=" + s.workflowId() + "&node="
                + java.net.URLEncoder.encode(s.nodeId(), StandardCharsets.UTF_8))
            .put("at", String.valueOf(last.at()))
            .set("ids", Json.obj().put("workflowId", s.workflowId().toString()).put("nodeId", s.nodeId())
                .set("runIds", runs));
      }
    }
    return new Evidence(items, chats, tools, steps);
  }

  /** The facts named, each once, still live and not restricted. */
  private static List<MemoryEntryEntity> facts(JsonNode p, ReviewDossier d) {
    List<MemoryEntryEntity> out = new ArrayList<>();
    Set<UUID> seen = new LinkedHashSet<>();
    for (JsonNode ref : p.path("facts")) {
      ReviewDossier.Fact f = d.facts.get(ref.asText("").strip());
      if (f == null) return List.of(); // one made-up reference spoils the suggestion
      MemoryEntryEntity e = f.entity();
      if (e.isRestricted()) return List.of();
      if (e.getStatus() != MemoryStatus.ACTIVE && e.getStatus() != MemoryStatus.PENDING) return List.of();
      if (seen.add(e.getId())) out.add(e);
    }
    return out;
  }

  private static ObjectNode factPayload(List<MemoryEntryEntity> facts) {
    ObjectNode payload = Json.obj();
    ArrayNode ids = payload.putArray("facts");
    ObjectNode hashes = payload.putObject("factHashes");
    for (MemoryEntryEntity f : facts) {
      ids.add(f.getId().toString());
      hashes.put(f.getId().toString(), f.getContentHash() + "|" + f.getStatus().name());
    }
    return payload;
  }

  private static String content(JsonNode p, int max) {
    String text = p.path("content").asText("").replaceAll("\\s+", " ").strip();
    if (text.isEmpty() || text.length() > max) return null;
    return text;
  }

  private static Drafted drafted(String kind, String summary, JsonNode p, ObjectNode payload,
                                 Evidence ev, int occurrences, String content,
                                 List<MemoryEntryEntity> facts, String subject) {
    String reason = ReviewDossier.clip(p.path("reason").asText(""), MAX_REASON_CHARS);
    StringBuilder key = new StringBuilder(kind).append('|');
    facts.stream().map(f -> f.getId().toString()).sorted().forEach(id -> key.append(id).append(','));
    key.append('|').append(subject == null ? "" : subject).append('|')
        .append(content == null ? "" : content.toLowerCase(Locale.ROOT));
    return new Drafted(kind, summary, reason == null || reason.isBlank() ? null : reason, payload,
        ev.items(), Math.max(1, occurrences), sha(key.toString()));
  }

  private static String times(int n) {
    return n == 1 ? "once" : n == 2 ? "twice" : n + " times";
  }

  private static String quoted(String name) {
    return name == null ? "a workflow" : "“" + name + "”";
  }

  static String sha(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

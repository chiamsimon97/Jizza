package com.jizza.engine.memory.review;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.knowledge.KnowledgeEntry;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.MemoryEntryEntity;
import com.jizza.engine.persistence.support.MemoryReviewQueries.FailedStep;
import com.jizza.engine.persistence.support.MemoryReviewQueries.FailedToolCall;
import com.jizza.engine.persistence.support.MemoryReviewQueries.UserMessage;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything one memory review reads, each item under a short reference ({@code f3}, {@code m12},
 * {@code t2}, {@code s1}, {@code a1}) so the model can point at it without ever handling an id.
 * The references are the only way a suggestion can name a fact, an agent or its evidence; anything
 * the model writes that is not one of them is ignored.
 *
 * <p>Failures are grouped: the same tool failing the same way five times is one item that
 * happened five times, which is what makes it worth a lesson. The count comes from here, never
 * from the model.
 */
final class ReviewDossier {

  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);
  private static final Pattern URL_HOST = Pattern.compile("https?://([A-Za-z0-9.-]+)");
  private static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");

  record Fact(String ref, MemoryEntryEntity entity, List<String> about) {}

  record Agent(String ref, AgentEntity entity) {}

  record Message(String ref, UserMessage message) {}

  record ToolFailures(String ref, UUID agentId, String agentName, String tool, String label,
                      String error, String host, List<FailedToolCall> calls) {}

  record StepFailures(String ref, UUID workflowId, String workflowName, String nodeId,
                      String nodeLabel, String nodeType, String host, String error,
                      List<FailedStep> steps) {}

  final Map<String, Fact> facts = new LinkedHashMap<>();
  final Map<String, Agent> agents = new LinkedHashMap<>();
  final Map<String, Message> messages = new LinkedHashMap<>();
  final Map<String, ToolFailures> tools = new LinkedHashMap<>();
  final Map<String, StepFailures> steps = new LinkedHashMap<>();
  final Map<UUID, List<KnowledgeEntry>> lessons = new LinkedHashMap<>();
  /** Older facts shown beside a changed one that reads like them; they alone are nothing new. */
  final java.util.Set<String> contextOnly = new java.util.HashSet<>();

  /**
   * @param changed     facts added or changed in the window (and any still waiting for the user)
   * @param neighbours  older facts that read like one of them, shown beside it
   * @param subjectName who each subject is, by id, so a fact says whom it is about
   */
  static ReviewDossier of(Collection<MemoryEntryEntity> changed, Collection<MemoryEntryEntity> neighbours,
                          Map<UUID, String> subjectName, List<AgentEntity> agents,
                          Map<UUID, List<KnowledgeEntry>> lessons, List<UserMessage> messages,
                          List<FailedToolCall> calls, List<FailedStep> failedSteps,
                          int maxMessageChars) {
    ReviewDossier d = new ReviewDossier();
    int n = 0;
    java.util.Set<UUID> seen = new java.util.HashSet<>();
    for (MemoryEntryEntity f : changed) {
      if (f.isRestricted() || !seen.add(f.getId())) continue;
      d.facts.put("f" + (++n), new Fact("f" + n, f, names(f, subjectName)));
    }
    for (MemoryEntryEntity f : neighbours) {
      if (f.isRestricted() || !seen.add(f.getId())) continue;
      d.facts.put("f" + (++n), new Fact("f" + n, f, names(f, subjectName)));
      d.contextOnly.add("f" + n);
    }
    n = 0;
    for (AgentEntity a : agents) d.agents.put("a" + (++n), new Agent("a" + n, a));
    d.lessons.putAll(lessons);
    n = 0;
    for (UserMessage m : messages) {
      if (m.content() == null || m.content().isBlank()) continue;
      d.messages.put("m" + (++n), new Message("m" + n, new UserMessage(m.messageId(),
          m.conversationId(), clip(m.content(), maxMessageChars), m.at())));
    }

    Map<String, List<FailedToolCall>> toolGroups = new LinkedHashMap<>();
    for (FailedToolCall c : calls) {
      if (c.tool() == null) continue;
      toolGroups.computeIfAbsent(c.agentId() + "|" + c.tool() + "|" + shape(c.error()),
          k -> new ArrayList<>()).add(c);
    }
    n = 0;
    for (List<FailedToolCall> group : toolGroups.values()) {
      FailedToolCall first = group.getFirst();
      String host = null;
      for (FailedToolCall c : group) {
        host = hostIn(c.error());
        if (host != null) break;
      }
      String ref = "t" + (++n);
      d.tools.put(ref, new ToolFailures(ref, first.agentId(), first.agentName(), first.tool(),
          first.label(), clip(first.error(), 300), host, List.copyOf(group)));
    }

    Map<String, List<FailedStep>> stepGroups = new LinkedHashMap<>();
    for (FailedStep s : failedSteps) {
      stepGroups.computeIfAbsent(s.workflowId() + "|" + s.nodeId() + "|" + shape(s.error()),
          k -> new ArrayList<>()).add(s);
    }
    n = 0;
    for (List<FailedStep> group : stepGroups.values()) {
      FailedStep first = group.getFirst();
      String host = hostOf(first.url());
      if (host == null) host = hostIn(first.error());
      String ref = "s" + (++n);
      d.steps.put(ref, new StepFailures(ref, first.workflowId(), first.workflowName(),
          first.nodeId(), first.nodeLabel(), first.nodeType(), host, clip(first.error(), 300),
          List.copyOf(group)));
    }
    return d;
  }

  /** Nothing new to read: no words from the user, no failures, no changed facts. */
  boolean isEmpty() {
    return messages.isEmpty() && tools.isEmpty() && steps.isEmpty()
        && facts.values().stream().noneMatch(f -> !contextOnly.contains(f.ref()));
  }

  /** The dossier as the model reads it: references, never ids. */
  String toPrompt(int maxChars) {
    ObjectNode root = Json.obj();
    ArrayNode agentList = root.putArray("agents");
    for (Agent a : agents.values()) {
      ObjectNode node = agentList.addObject().put("ref", a.ref()).put("name", a.entity().getName());
      if (a.entity().isOrchestrator()) node.put("role", "the main assistant");
      else if (a.entity().getDomain() != null) node.put("area", a.entity().getDomain());
      List<KnowledgeEntry> known = lessons.getOrDefault(a.entity().getId(), List.of());
      if (!known.isEmpty()) {
        ArrayNode list = node.putArray("lessons_already_known");
        for (KnowledgeEntry l : known) {
          list.add((l.lesson().aboutLabel() == null ? "" : l.lesson().aboutLabel() + ": ") + l.content());
        }
      }
    }
    ArrayNode factList = root.putArray("facts");
    for (Fact f : facts.values()) {
      MemoryEntryEntity e = f.entity();
      ObjectNode node = factList.addObject().put("ref", f.ref()).put("says", e.getContent())
          .put("category", lower(e.getCategory())).put("status", lower(e.getStatus()))
          .put("confirmed_by_user", e.isConfirmed()).put("source", lower(e.getSource()))
          .put("updated", e.getUpdatedAt() == null ? null : WHEN.format(e.getUpdatedAt()));
      if (e.getFactKey() != null) node.put("key", e.getFactKey());
      if (!f.about().isEmpty()) node.set("about", Json.toNode(f.about()));
      if (contextOnly.contains(f.ref())) node.put("older", true);
    }
    ArrayNode toolList = root.putArray("failed_tool_calls");
    for (ToolFailures t : tools.values()) {
      ObjectNode node = toolList.addObject().put("ref", t.ref()).put("agent", agentRef(t.agentId()))
          .put("tool", t.tool()).put("times", t.calls().size()).put("error", t.error());
      if (t.label() != null) node.put("what", t.label());
      if (t.host() != null) node.put("site", t.host());
      ArrayNode asked = node.putArray("while_asked");
      t.calls().stream().map(FailedToolCall::jobTitle).filter(Objects::nonNull).distinct().limit(3)
          .forEach(title -> asked.add(clip(title, 120)));
    }
    ArrayNode stepList = root.putArray("failed_workflow_steps");
    for (StepFailures s : steps.values()) {
      ObjectNode node = stepList.addObject().put("ref", s.ref()).put("workflow", s.workflowName())
          .put("step", s.nodeLabel() == null ? s.nodeId() : s.nodeLabel())
          .put("times", s.steps().size()).put("error", s.error());
      if (s.nodeType() != null) node.put("type", s.nodeType());
      if (s.host() != null) node.put("site", s.host());
    }
    ArrayNode said = root.putArray("user_said");
    for (Message m : messages.values()) {
      said.addObject().put("ref", m.ref()).put("at", WHEN.format(m.message().at()))
          .put("text", m.message().content());
    }
    String text = Json.write(root);
    // The user's words are the bulkiest part; drop the oldest until it fits.
    while (text.length() > maxChars && !said.isEmpty()) {
      said.remove(0);
      text = Json.write(root);
    }
    return text;
  }

  String agentRef(UUID agentId) {
    if (agentId == null) return null;
    return agents.values().stream().filter(a -> a.entity().getId().equals(agentId))
        .map(Agent::ref).findFirst().orElse(null);
  }

  Agent orchestrator() {
    return agents.values().stream().filter(a -> a.entity().isOrchestrator()).findFirst().orElse(null);
  }

  /** Whether {@code host} appears anywhere the review read, so a suggested site is a real one. */
  boolean mentionsHost(String host) {
    if (host == null) return false;
    for (ToolFailures t : tools.values()) {
      if (host.equals(t.host()) || contains(t.error(), host)) return true;
    }
    for (StepFailures s : steps.values()) {
      if (host.equals(s.host()) || contains(s.error(), host)) return true;
    }
    for (Message m : messages.values()) {
      if (contains(m.message().content(), host)) return true;
    }
    return false;
  }

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────

  /** An error with its numbers and ids blanked, so the same failure groups together. */
  static String shape(String error) {
    if (error == null) return "";
    String s = error.toLowerCase(Locale.ROOT)
        .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "#")
        .replaceAll("\\d+", "#")
        .replaceAll("\\s+", " ").strip();
    return s.length() <= 140 ? s : s.substring(0, 140);
  }

  static String hostIn(String text) {
    if (text == null) return null;
    Matcher m = URL_HOST.matcher(text);
    return m.find() ? normaliseHost(m.group(1)) : null;
  }

  static String hostOf(String url) {
    if (url == null || url.isBlank()) return null;
    try {
      String host = URI.create(url.strip()).getHost();
      return host == null ? hostIn(url) : normaliseHost(host);
    } catch (IllegalArgumentException e) {
      return hostIn(url);
    }
  }

  /** Lower case, without "www.", and only if it looks like a host name at all. */
  static String normaliseHost(String host) {
    if (host == null) return null;
    String h = host.strip().toLowerCase(Locale.ROOT);
    if (h.startsWith("www.")) h = h.substring(4);
    while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
    return HOST.matcher(h).matches() && h.length() <= 253 ? h : null;
  }

  private static boolean contains(String text, String host) {
    return text != null && text.toLowerCase(Locale.ROOT).contains(host);
  }

  private static List<String> names(MemoryEntryEntity f, Map<UUID, String> subjectName) {
    List<String> out = new ArrayList<>();
    for (UUID id : f.getSubjectIds()) {
      String name = subjectName.get(id);
      if (name != null) out.add(name);
    }
    return out;
  }

  static String clip(String text, int max) {
    if (text == null) return null;
    String one = text.replaceAll("\\s+", " ").strip();
    return one.length() <= max ? one : one.substring(0, Math.max(1, max - 1)) + "…";
  }

  private static String lower(Enum<?> value) {
    return value == null ? null : value.name().toLowerCase(Locale.ROOT);
  }

  static String when(Instant at) {
    return at == null ? null : WHEN.format(at);
  }
}

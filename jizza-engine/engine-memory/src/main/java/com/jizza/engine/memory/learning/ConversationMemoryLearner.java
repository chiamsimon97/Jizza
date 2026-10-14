package com.jizza.engine.memory.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.DataClassification;
import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.enums.MemorySource;
import com.jizza.engine.api.enums.MemoryWriteOutcome;
import com.jizza.engine.api.enums.SubjectKind;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.memory.Actor;
import com.jizza.engine.api.memory.MemoryDraft;
import com.jizza.engine.api.memory.MemoryLearner;
import com.jizza.engine.api.memory.MemoryScope;
import com.jizza.engine.api.memory.MemoryStore;
import com.jizza.engine.api.memory.MemorySubject;
import com.jizza.engine.api.memory.MemoryWrite;
import com.jizza.engine.api.memory.SubjectDraft;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.memory.config.MemoryProperties;
import com.jizza.engine.memory.config.MemoryScopes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Promotes durable facts from a finished chat turn into long-term memory.
 *
 * <p>Selective on purpose. A cheap model reads the turn and proposes only facts that will still be
 * true and useful later ("my wife Priya's risk appetite is moderate"), never chit-chat, one-off
 * requests or anything the assistant merely said. Proposals under the confidence threshold are
 * dropped; the rest are stored unconfirmed, marked as learned from that message, and shown to the
 * user, who can confirm, correct or delete them. Credentials are never stored.
 *
 * <p>Only the user's own words are a source. The assistant's reply is included so "her" and "it"
 * can be resolved, but it may quote connector output, which is untrusted (R10); a fact that appears
 * only there is not the user's and is not kept.
 *
 * <p>Runs off the request thread and swallows its own failures: learning must never cost the user
 * their reply. Skipped entirely when extraction is disabled, the user turned auto-learn off, the
 * spend ceiling is reached, or the message is too short to hold a fact.
 */
@Service
public class ConversationMemoryLearner implements MemoryLearner {

  private static final Logger log = LoggerFactory.getLogger(ConversationMemoryLearner.class);

  static final String SYSTEM_PROMPT = """
      You maintain a personal assistant's long-term memory about its user. Read one conversation
      turn and extract only DURABLE facts worth remembering for future conversations: who the
      people in the user's life are and how they relate to the user, their accounts, assets,
      investment or health plans, goals, preferences, constraints, and standing instructions.

      Rules:
      - Extract only what the USER stated or clearly confirmed. The assistant's reply is context
        for resolving pronouns, never a source of facts.
      - Skip greetings, one-off requests, questions, speculation and anything temporary.
      - Never extract passwords, PINs, card numbers, tokens or other credentials.
      - One fact per item, written as a standalone sentence naming who it is about.
      - "key" is a short snake_case attribute the fact sets (e.g. risk_tolerance, monthly_sip,
        date_of_birth) when the fact is a value that could later change; otherwise omit it.
      - "about" lists the people or things the fact concerns (omit for facts about the user).
      - Use "links" to record how subjects relate, e.g. an account held by a person.
      - confidence: how certain you are the user meant this as a lasting fact (0 to 1).
      - importance: 0.9 or more only for core identity facts (names of close family, etc.).

      Reply with JSON only, no prose:
      {"facts":[{"content":"...","category":"profile|preference|relationship|finance|health|professional|project|task|general",
                 "key":"optional","about":[{"name":"...","kind":"person|account|asset|plan|organization|place|other","relation":"optional, how it relates to the user"}],
                 "classification":"none|pii|health|financial","confidence":0.0,"importance":0.0,"expires_in_days":null}],
       "links":[{"from":"subject name","to":"subject name","relation":"held_by|plan_for|..."}]}
      Reply {"facts":[],"links":[]} when there is nothing worth keeping.
      """;

  private final MemoryStore store;
  private final LlmClient llm;
  private final CostAccountant costs;
  private final MemoryScopes scopes;
  private final MemoryProperties props;
  private final AuditLog audit;
  private final ExecutorService executor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jizza-memory-learn-", 0).factory());

  public ConversationMemoryLearner(MemoryStore store, LlmClient llm, CostAccountant costs,
                                   MemoryScopes scopes, MemoryProperties props, AuditLog audit) {
    this.store = store;
    this.llm = llm;
    this.costs = costs;
    this.scopes = scopes;
    this.props = props;
    this.audit = audit;
  }

  @Override
  public void afterTurn(Turn turn) {
    if (!shouldLearn(turn)) return;
    if (props.getExtraction().isAsync()) {
      executor.execute(() -> safely(turn));
    } else {
      safely(turn);
    }
  }

  private boolean shouldLearn(Turn turn) {
    MemoryProperties.Extraction cfg = props.getExtraction();
    if (!props.isEnabled() || !cfg.isEnabled()) return false;
    if (turn.userText() == null || turn.userText().strip().length() < cfg.getMinMessageChars()) return false;
    return true;
  }

  private void safely(Turn turn) {
    try {
      learn(turn);
    } catch (RuntimeException e) {
      log.warn("Could not learn from conversation turn {}: {}", turn.userMessageId(), e.getMessage());
    }
  }

  /** Package-private so tests can drive it directly. Returns how many facts were stored or reinforced. */
  int learn(Turn turn) {
    if (!store.settings(turn.userId()).autoLearn()) return 0;
    if (!costs.withinBudget(turn.userId())) return 0;

    List<MemorySubject> known = new ArrayList<>(store.subjects(turn.userId(), MemoryScope.everything()));
    String model = llm.modelFor(props.getExtraction().getTier());
    LlmResponse response = llm.complete(model,
        List.of(Msg.system(SYSTEM_PROMPT), Msg.user(prompt(turn, known))), List.of());
    costs.record(turn.userId(), response.model() == null ? model : response.model(),
        response.inputTokens(), response.outputTokens(), "memory:extract");

    JsonNode parsed = parse(response.text());
    if (parsed == null) return 0;

    Actor actor = Actor.system();
    int stored = 0;
    int pending = 0;
    List<UUID> touched = new ArrayList<>();
    int max = props.getExtraction().getMaxFactsPerTurn();
    for (JsonNode fact : parsed.path("facts")) {
      if (stored + pending >= max) break;
      double confidence = fact.path("confidence").asDouble(0);
      if (confidence < props.getExtraction().getMinConfidence()) continue;
      String content = fact.path("content").asText("").strip();
      MemoryCategory category = enumOr(MemoryCategory.class, fact.path("category").asText(), null);
      if (content.isEmpty() || category == null) continue;

      List<UUID> about = new ArrayList<>();
      for (JsonNode ref : fact.path("about")) {
        UUID id = subject(turn.userId(), known, ref.path("name").asText(""),
            enumOr(SubjectKind.class, ref.path("kind").asText(), null),
            ref.path("relation").asText(null), category, actor);
        if (id != null && !about.contains(id)) about.add(id);
      }
      Integer expiresInDays = fact.path("expires_in_days").isInt() ? fact.path("expires_in_days").asInt() : null;
      MemoryDraft draft = new MemoryDraft(category, content, fact.path("key").asText(null), null,
          enumOr(DataClassification.class, fact.path("classification").asText(), null),
          confidence, fact.has("importance") ? fact.path("importance").asDouble() : null,
          MemorySource.CONVERSATION, "message:" + turn.userMessageId(), false, about, null,
          expiresInDays == null || expiresInDays <= 0 ? null
              : Instant.now().plus(Duration.ofDays(expiresInDays)));
      try {
        MemoryWrite write = store.remember(turn.userId(), draft, actor);
        touched.add(write.memory().id());
        if (write.outcome() == MemoryWriteOutcome.PENDING_CONFLICT) pending++;
        else stored++;
      } catch (RuntimeException e) {
        log.debug("Skipped a proposed memory: {}", e.getMessage());
      }
    }

    for (JsonNode link : parsed.path("links")) {
      UUID from = existing(turn.userId(), link.path("from").asText(""));
      UUID to = existing(turn.userId(), link.path("to").asText(""));
      String relation = link.path("relation").asText("");
      if (from != null && to != null && !from.equals(to) && !relation.isBlank()) {
        try {
          store.relate(turn.userId(), from, to, relation, actor);
        } catch (RuntimeException e) {
          log.debug("Skipped a proposed subject link: {}", e.getMessage());
        }
      }
    }

    if (stored + pending > 0) {
      audit.record(turn.userId(), ActorType.SYSTEM, null, "memory.learned",
          turn.conversationId() == null ? null : turn.conversationId().toString(),
          Json.obj().put("facts", stored).put("pendingConfirmation", pending));
    }
    return stored + pending;
  }

  private String prompt(Turn turn, List<MemorySubject> known) {
    ObjectNode body = Json.obj();
    ArrayNode subjects = body.putArray("known_subjects");
    for (MemorySubject s : known) {
      ObjectNode node = subjects.addObject();
      node.put("name", s.name());
      node.put("kind", s.kind().name().toLowerCase(Locale.ROOT));
      if (s.relation() != null) node.put("relation", s.relation());
      if (!s.aliases().isEmpty()) node.set("aliases", Json.toNode(s.aliases()));
    }
    body.put("user_said", turn.userText());
    if (turn.assistantText() != null) body.put("assistant_replied", turn.assistantText());
    return "Use the known subjects' exact names when a fact is about one of them.\n\n" + Json.write(body);
  }

  /** The id of the named subject, creating it when it is new to memory. */
  private UUID subject(UUID userId, List<MemorySubject> known, String name, SubjectKind kind,
                       String relation, MemoryCategory category, Actor actor) {
    if (name == null || name.isBlank()) return null;
    for (MemorySubject s : known) {
      if (matches(s, name) && (kind == null || kind == s.kind())) return s.id();
    }
    SubjectKind resolved = kind == null ? SubjectKind.OTHER : kind;
    boolean domainBound = resolved == SubjectKind.ACCOUNT || resolved == SubjectKind.ASSET
        || resolved == SubjectKind.PLAN;
    MemorySubject created = store.upsertSubject(userId, new SubjectDraft(resolved, name.strip(),
        relation, null, null, null, domainBound ? scopes.defaultDomains(category) : List.of()), actor);
    known.add(created);
    return created.id();
  }

  private UUID existing(UUID userId, String name) {
    if (name.isBlank()) return null;
    return store.subjects(userId, MemoryScope.everything()).stream()
        .filter(s -> matches(s, name)).map(MemorySubject::id).findFirst().orElse(null);
  }

  private static boolean matches(MemorySubject s, String name) {
    String wanted = name.strip();
    return s.name().equalsIgnoreCase(wanted)
        || s.aliases().stream().anyMatch(a -> a.equalsIgnoreCase(wanted))
        || (s.relation() != null && s.relation().equalsIgnoreCase(wanted));
  }

  /** The model's reply should be bare JSON; tolerate a code fence or a sentence around it. */
  static JsonNode parse(String text) {
    if (text == null) return null;
    int start = text.indexOf('{');
    int end = text.lastIndexOf('}');
    if (start < 0 || end <= start) return null;
    try {
      JsonNode node = Json.mapper().readTree(text.substring(start, end + 1));
      return node.isObject() ? node : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static <E extends Enum<E>> E enumOr(Class<E> type, String value, E fallback) {
    if (value == null || value.isBlank()) return fallback;
    try {
      return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return fallback;
    }
  }

  @jakarta.annotation.PreDestroy
  void shutdown() {
    executor.shutdown();
  }
}

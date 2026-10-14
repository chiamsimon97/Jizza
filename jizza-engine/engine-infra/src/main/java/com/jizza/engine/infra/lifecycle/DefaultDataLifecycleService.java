package com.jizza.engine.infra.lifecycle;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.storage.StorageProvider;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.FileEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.AuditLogRepository;
import com.jizza.engine.persistence.repository.AutonomyPolicyRepository;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import com.jizza.engine.persistence.repository.ConversationRepository;
import com.jizza.engine.persistence.repository.FileRepository;
import com.jizza.engine.persistence.repository.KnowledgeEntryRepository;
import com.jizza.engine.persistence.repository.MemoryEntryRepository;
import com.jizza.engine.persistence.repository.MemorySubjectRelationRepository;
import com.jizza.engine.persistence.repository.MemorySubjectRepository;
import com.jizza.engine.persistence.repository.MessageRepository;
import com.jizza.engine.persistence.repository.NotificationRepository;
import com.jizza.engine.persistence.repository.ScheduledTaskRepository;
import com.jizza.engine.persistence.repository.UserRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.infra.config.PlatformProperties;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data lifecycle (R48): full export, hard delete, and history pruning.
 *
 * <p>Hard delete really does mean it. Every user-owned table cascades from {@code users}, so
 * deleting the row removes the lot; stored files are removed from disk first, because a
 * filesystem has no foreign keys to cascade through.
 */
@Service
public class DefaultDataLifecycleService implements com.jizza.engine.api.lifecycle.DataLifecycleService {

  private static final Logger log = LoggerFactory.getLogger(DefaultDataLifecycleService.class);

  private final UserRepository users;
  private final AgentRepository agents;
  private final KnowledgeEntryRepository knowledge;
  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final ConnectionRepository connections;
  private final WorkflowRepository workflows;
  private final ScheduledTaskRepository tasks;
  private final NotificationRepository notifications;
  private final AutonomyPolicyRepository policies;
  private final AuditLogRepository auditEntries;
  private final FileRepository files;
  private final StorageProvider storage;
  private final AuditLog audit;
  private final PlatformProperties props;
  private final MemoryEntryRepository memories;
  private final MemorySubjectRepository memorySubjects;
  private final MemorySubjectRelationRepository memoryRelations;

  public DefaultDataLifecycleService(UserRepository users, AgentRepository agents,
                              KnowledgeEntryRepository knowledge,
                              ConversationRepository conversations, MessageRepository messages,
                              ConnectionRepository connections, WorkflowRepository workflows,
                              ScheduledTaskRepository tasks, NotificationRepository notifications,
                              AutonomyPolicyRepository policies, AuditLogRepository auditEntries,
                              FileRepository files, StorageProvider storage, AuditLog audit,
                              PlatformProperties props, MemoryEntryRepository memories,
                              MemorySubjectRepository memorySubjects,
                              MemorySubjectRelationRepository memoryRelations) {
    this.users = users;
    this.agents = agents;
    this.knowledge = knowledge;
    this.conversations = conversations;
    this.messages = messages;
    this.connections = connections;
    this.workflows = workflows;
    this.tasks = tasks;
    this.notifications = notifications;
    this.policies = policies;
    this.auditEntries = auditEntries;
    this.files = files;
    this.storage = storage;
    this.audit = audit;
    this.props = props;
    this.memories = memories;
    this.memorySubjects = memorySubjects;
    this.memoryRelations = memoryRelations;
  }

  /**
   * Everything the platform holds about the user, as JSON.
   *
   * <p>Credentials are reported as present rather than included: an export is a file that leaves
   * the machine, and a live third-party token has no business in one.
   */
  @Override
  @Transactional(readOnly = true)
  public ObjectNode export(UUID userId) {
    ObjectNode root = Json.obj();
    root.put("exportedAt", Instant.now().toString());

    users.findById(userId).ifPresent(user -> {
      ObjectNode account = root.putObject("account");
      account.put("id", user.getId().toString());
      account.put("email", user.getEmail());
      account.put("mfaEnabled", user.isMfaEnabled());
      account.put("createdAt", String.valueOf(user.getCreatedAt()));
    });

    ArrayNode agentArray = root.putArray("agents");
    agents.findByUserIdOrderByIsOrchestratorDescNameAsc(userId).forEach(agent -> {
      ObjectNode node = agentArray.addObject();
      node.put("key", agent.getKey());
      node.put("name", agent.getName());
      node.put("domain", agent.getDomain());
      node.put("enabled", agent.isEnabled());

      ArrayNode entries = node.putArray("knowledge");
      knowledge.findByAgentIdOrderByUpdatedAtDesc(agent.getId(), PageRequest.of(0, 10_000))
          .forEach(entry -> {
            ObjectNode e = entries.addObject();
            e.put("title", entry.getTitle());
            e.put("content", entry.getContent());
            e.put("classification", String.valueOf(entry.getClassification()));
            if (entry.getData() != null) e.set("data", entry.getData());
          });
    });

    ObjectNode memory = root.putObject("memory");
    ArrayNode subjectArray = memory.putArray("subjects");
    memorySubjects.findByUserIdOrderByNameAsc(userId).forEach(subject -> {
      ObjectNode node = subjectArray.addObject();
      node.put("id", subject.getId().toString());
      node.put("kind", String.valueOf(subject.getKind()).toLowerCase(java.util.Locale.ROOT));
      node.put("name", subject.getName());
      node.put("relation", subject.getRelation());
      node.set("aliases", Json.toNode(subject.getAliases()));
      if (subject.getAttributes() != null) node.set("attributes", subject.getAttributes());
      node.set("domains", Json.toNode(subject.getDomains()));
    });
    ArrayNode relationArray = memory.putArray("relations");
    memoryRelations.findByUserId(userId).forEach(relation -> relationArray.addObject()
        .put("from", relation.getFromSubjectId().toString())
        .put("to", relation.getToSubjectId().toString())
        .put("relation", relation.getRelation()));
    ArrayNode factArray = memory.putArray("facts");
    memories.findAll((r, q, cb) -> cb.equal(r.get("userId"), userId)).forEach(fact -> {
      ObjectNode node = factArray.addObject();
      node.put("category", String.valueOf(fact.getCategory()).toLowerCase(java.util.Locale.ROOT));
      node.put("content", fact.getContent());
      node.put("key", fact.getFactKey());
      if (fact.getData() != null) node.set("data", fact.getData());
      node.put("status", String.valueOf(fact.getStatus()).toLowerCase(java.util.Locale.ROOT));
      node.put("confirmed", fact.isConfirmed());
      node.put("source", String.valueOf(fact.getSource()).toLowerCase(java.util.Locale.ROOT));
      node.set("about", Json.toNode(fact.getSubjectIds().stream().map(Object::toString).toList()));
      node.set("domains", Json.toNode(fact.getDomains()));
      node.put("updatedAt", String.valueOf(fact.getUpdatedAt()));
    });

    ArrayNode conversationArray = root.putArray("conversations");
    conversations.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1_000))
        .forEach(conversation -> {
          ObjectNode node = conversationArray.addObject();
          node.put("createdAt", String.valueOf(conversation.getCreatedAt()));
          ArrayNode messageArray = node.putArray("messages");
          messages.findByConversationIdOrderByCreatedAtAsc(conversation.getId()).forEach(message -> {
            ObjectNode m = messageArray.addObject();
            m.put("role", String.valueOf(message.getRole()).toLowerCase(java.util.Locale.ROOT));
            m.put("content", message.getContent());
            m.put("at", String.valueOf(message.getCreatedAt()));
          });
        });

    ArrayNode connectionArray = root.putArray("connections");
    connections.findByUserIdOrderByServiceNameAsc(userId).forEach(connection -> {
      ObjectNode node = connectionArray.addObject();
      node.put("service", connection.getServiceName());
      node.put("authScheme", String.valueOf(connection.getAuthScheme()));
      node.put("status", String.valueOf(connection.getStatus()));
      node.put("credentialsStored", connection.getAccessToken() != null
          || connection.getClientSecret() != null);
    });

    ArrayNode workflowArray = root.putArray("workflows");
    workflows.findByUserIdOrderByCreatedAtDesc(userId).forEach(workflow -> {
      ObjectNode node = workflowArray.addObject();
      node.put("name", workflow.getName());
      node.put("origin", String.valueOf(workflow.getOrigin()));
      node.put("status", String.valueOf(workflow.getStatus()));
      node.put("version", workflow.getVersion());
      node.set("graph", workflow.getGraph());
    });

    ArrayNode taskArray = root.putArray("tasks");
    tasks.findByUserIdOrderByNextRunAtAsc(userId).forEach(task -> {
      ObjectNode node = taskArray.addObject();
      node.put("title", task.getTitle());
      node.put("scheduleType", String.valueOf(task.getScheduleType()));
      node.put("schedule", task.getCronOrRrule());
      node.put("nextRunAt", String.valueOf(task.getNextRunAt()));
      node.put("followsProcedure", task.getBoundWorkflowId() != null);
    });

    ArrayNode policyArray = root.putArray("autonomyPolicies");
    policies.findByUserIdOrderByPrecedenceDesc(userId).forEach(policy -> {
      ObjectNode node = policyArray.addObject();
      node.put("agentId", String.valueOf(policy.getAgentId()));
      node.put("toolRef", policy.getToolRef());
      node.put("rule", String.valueOf(policy.getRule()));
      node.put("precedence", policy.getPrecedence());
    });

    ArrayNode auditArray = root.putArray("activity");
    auditEntries.findByUserIdOrderBySeqAsc(userId).forEach(entry -> {
      ObjectNode node = auditArray.addObject();
      node.put("at", String.valueOf(entry.getCreatedAt()));
      node.put("actorType", String.valueOf(entry.getActorType()));
      node.put("action", entry.getAction());
      node.put("target", entry.getTarget());
      node.put("hash", entry.getHash());
    });

    audit.record(userId, ActorType.USER, userId, "data.exported", null,
        Json.obj().put("agents", agentArray.size()).put("workflows", workflowArray.size()));
    return root;
  }

  /** "Forget everything": irreversible, and audited immediately before the row disappears. */
  @Override
  @Transactional
  public void hardDelete(UUID userId) {
    audit.record(userId, ActorType.USER, userId, "data.hard_delete_requested", null, null);

    for (FileEntity file : files.findByUserId(userId)) {
      try {
        storage.delete(file.getStorageRef());
      } catch (RuntimeException e) {
        log.warn("Could not delete stored file {} during hard delete", file.getStorageRef(), e);
      }
    }
    // Every user-owned table cascades from users, so one delete clears the rest.
    users.deleteById(userId);
    log.warn("Hard-deleted all data for user {}", userId);
  }

  /** Conversation-history pruning (R48). Knowledge is not pruned: the user curates that. */
  @Scheduled(cron = "${jizza.platform.retention.cron:0 30 3 * * *}")
  @Transactional
  public void pruneHistory() {
    var window = props.getRetention().getConversationHistory();
    if (window == null || window.isZero() || window.isNegative()) return;

    int pruned = messages.pruneOlderThan(Instant.now().minus(window));
    if (pruned > 0) log.info("Pruned {} conversation message(s) older than {}", pruned, window);
  }
}

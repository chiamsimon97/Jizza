package com.jizza.orchestrator.chat;

import com.jizza.common.agent.Agent;
import com.jizza.common.agent.AgentRequest;
import com.jizza.common.agent.AgentResult;
import com.jizza.common.audit.AuditLog;
import com.jizza.common.enums.ActorType;
import com.jizza.common.enums.MessageRole;
import com.jizza.common.enums.ModelTier;
import com.jizza.common.error.NotFoundException;
import com.jizza.common.llm.Msg;
import com.jizza.common.orchestrator.AssistantReply;
import com.jizza.common.orchestrator.Orchestrator;
import com.jizza.common.realtime.RealtimeGateway;
import com.jizza.common.util.Json;
import com.jizza.orchestrator.delegation.DelegationRecorder;
import com.jizza.persistence.entity.AgentEntity;
import com.jizza.persistence.entity.ConversationEntity;
import com.jizza.persistence.entity.DelegationTraceEntity;
import com.jizza.persistence.entity.MessageEntity;
import com.jizza.persistence.repository.AgentRepository;
import com.jizza.persistence.repository.ConversationRepository;
import com.jizza.persistence.repository.DelegationTraceRepository;
import com.jizza.persistence.repository.MessageRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Jizza. The only entry point for a user message (R18), and the only thing that produces a reply
 * the user sees.
 *
 * <p>Specialists are reachable exclusively through the delegation tool, so there is no code path by
 * which a specialist's output reaches the user unrelayed. What they contributed is recorded in
 * {@code delegation_traces} for debugging and surfaced as named attributions on the reply.
 */
@Service
public class JizzaOrchestrator implements Orchestrator {

  /** How many prior turns are replayed into the model as conversation context. */
  private static final int HISTORY_TURNS = 20;

  private final AgentRepository agents;
  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final DelegationTraceRepository traces;
  private final Agent baseAgent;
  private final DelegationRecorder recorder;
  private final AuditLog audit;
  private final ObjectProvider<RealtimeGateway> realtime;

  public JizzaOrchestrator(AgentRepository agents, ConversationRepository conversations,
                           MessageRepository messages, DelegationTraceRepository traces,
                           Agent baseAgent, DelegationRecorder recorder, AuditLog audit,
                           ObjectProvider<RealtimeGateway> realtime) {
    this.agents = agents;
    this.conversations = conversations;
    this.messages = messages;
    this.traces = traces;
    this.baseAgent = baseAgent;
    this.recorder = recorder;
    this.audit = audit;
    this.realtime = realtime;
  }

  @Override
  public AssistantReply handle(UUID userId, String message) {
    return handle(userId, null, message);
  }

  /**
   * Deliberately not transactional. A turn spans model calls and, on a cross-domain request, work
   * fanned out across threads; holding a transaction open across that would pin a connection for
   * the whole turn and let the fan-out wait on locks its own caller is holding. Each persistence
   * step is short and atomic instead.
   */
  @Override
  public AssistantReply handle(UUID userId, UUID conversationId, String message) {
    AgentEntity jizza = agents.findByUserIdAndIsOrchestratorTrue(userId)
        .orElseThrow(() -> new NotFoundException("Orchestrator agent for user", userId));

    ConversationEntity conversation = resolveConversation(userId, conversationId);
    MessageEntity userMessage = persist(conversation.getId(), MessageRole.USER, message);

    audit.record(userId, ActorType.USER, userId, "chat.message", conversation.getId().toString(),
        Json.obj().put("messageId", userMessage.getId().toString()));

    recorder.beginTurn();
    AgentResult result;
    List<DelegationRecorder.Contribution> contributions;
    try {
      result = baseAgent.run(new AgentRequest(userId, jizza.getId(), message, null,
          history(conversation.getId()), ModelTier.STANDARD, false, null));
    } finally {
      contributions = recorder.endTurn();
    }

    String text = result.text() == null || result.text().isBlank()
        ? "I could not put together an answer for that."
        : result.text();
    MessageEntity assistantMessage = persist(conversation.getId(), MessageRole.ASSISTANT, text);
    recordTraces(jizza, assistantMessage.getId(), contributions);

    AssistantReply reply = new AssistantReply(conversation.getId(), assistantMessage.getId(), text,
        contributions.stream()
            .map(c -> new AssistantReply.Contribution(c.agentId(), c.agentName(), c.task()))
            .toList());

    realtime.ifAvailable(gateway -> gateway.publishChatChunk(userId, conversation.getId(), text, true));
    return reply;
  }

  private ConversationEntity resolveConversation(UUID userId, UUID conversationId) {
    if (conversationId != null) {
      ConversationEntity existing = conversations.findById(conversationId)
          .orElseThrow(() -> new NotFoundException("Conversation", conversationId));
      if (!existing.getUserId().equals(userId)) {
        throw new NotFoundException("Conversation", conversationId);
      }
      return existing;
    }
    return conversations.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1)).stream()
        .findFirst()
        .orElseGet(() -> {
          ConversationEntity created = new ConversationEntity();
          created.setUserId(userId);
          return conversations.save(created);
        });
  }

  private MessageEntity persist(UUID conversationId, MessageRole role, String content) {
    MessageEntity entity = new MessageEntity();
    entity.setConversationId(conversationId);
    entity.setRole(role);
    entity.setContent(content);
    return messages.save(entity);
  }

  /** Recent turns, oldest first, excluding the message just written for this turn. */
  private List<Msg> history(UUID conversationId) {
    List<MessageEntity> recent = new ArrayList<>(messages.findByConversationIdOrderByCreatedAtDesc(
        conversationId, PageRequest.of(0, HISTORY_TURNS + 1)));
    Collections.reverse(recent);
    if (!recent.isEmpty()) recent.remove(recent.size() - 1);
    return recent.stream()
        .map(m -> m.getRole() == MessageRole.USER
            ? Msg.user(m.getContent()) : Msg.assistant(m.getContent()))
        .toList();
  }

  private void recordTraces(AgentEntity jizza, UUID messageId,
                            List<DelegationRecorder.Contribution> contributions) {
    for (DelegationRecorder.Contribution contribution : contributions) {
      DelegationTraceEntity trace = new DelegationTraceEntity();
      trace.setMessageId(messageId);
      trace.setFromAgent(jizza.getId());
      trace.setToAgent(contribution.agentId());
      trace.setTask(contribution.task());
      trace.setResult(contribution.result());
      traces.save(trace);
    }
  }
}

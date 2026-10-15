package com.jizza.engine.chat.turn;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.chat.TurnRef;
import com.jizza.engine.api.enums.MessageRole;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.chat.activity.JobSummaries;
import com.jizza.engine.chat.config.ChatProperties;
import com.jizza.engine.persistence.entity.ConversationEntity;
import com.jizza.engine.persistence.entity.DelegationTraceEntity;
import com.jizza.engine.persistence.entity.ChatJobEntity;
import com.jizza.engine.persistence.entity.MessageEntity;
import com.jizza.engine.persistence.repository.ConversationRepository;
import com.jizza.engine.persistence.repository.DelegationTraceRepository;
import com.jizza.engine.persistence.repository.ChatJobRepository;
import com.jizza.engine.persistence.repository.MessageRepository;
import com.jizza.engine.api.orchestrator.Orchestrator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The short, atomic writes a turn is made of. A turn itself is never one long transaction. */
@Component
public class TurnStore {

  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final ChatJobRepository jobs;
  private final DelegationTraceRepository traces;
  private final ChatProperties props;

  public TurnStore(ConversationRepository conversations, MessageRepository messages,
                   ChatJobRepository jobs, DelegationTraceRepository traces, ChatProperties props) {
    this.props = props;
    this.conversations = conversations;
    this.messages = messages;
    this.jobs = jobs;
    this.traces = traces;
  }

  /** A send that was already accepted, found by the id the client gave it. */
  @Transactional(readOnly = true)
  public Optional<TurnRef> existing(UUID userId, UUID conversationId, UUID clientMessageId) {
    if (conversationId == null || clientMessageId == null) return Optional.empty();
    owned(userId, conversationId);
    return messages.findByConversationIdAndClientMessageId(conversationId, clientMessageId)
        .flatMap(m -> m.getJobId() == null ? Optional.empty() : jobs.findById(m.getJobId()))
        .map(j -> new TurnRef(j.getId(), conversationId, j.getUserMessageId(),
            j.getAssistantMessageId(), false));
  }

  /**
   * Stores the user's message, an empty reply that will stream into, and the job, together. A
   * conversation already answering a message refuses a second one rather than interleaving them.
   */
  @Transactional
  public TurnRef begin(UUID userId, UUID conversationId, UUID clientMessageId, String text) {
    ConversationEntity conversation = conversationId == null ? create(userId) : owned(userId, conversationId);

    ChatJobEntity job = new ChatJobEntity();
    job.setUserId(userId);
    job.setConversationId(conversation.getId());
    job.setOrigin("chat");
    job.setStatus(ChatJobEntity.RUNNING);
    job.setTitle(JobSummaries.title(text));
    try {
      job = jobs.saveAndFlush(job);
    } catch (DataIntegrityViolationException e) {
      throw new ConflictException("Jizza is still answering your last message in this "
          + "conversation. Wait for it, or stop it, before sending another.");
    }

    MessageEntity user = message(conversation.getId(), MessageRole.USER, text, MessageEntity.COMPLETE, job.getId());
    user.setClientMessageId(clientMessageId);
    user = messages.save(user);
    MessageEntity reply = messages.save(
        message(conversation.getId(), MessageRole.ASSISTANT, "", MessageEntity.STREAMING, job.getId()));

    job.setUserMessageId(user.getId());
    job.setAssistantMessageId(reply.getId());

    Instant now = Instant.now();
    conversation.setLastMessageAt(now);
    conversation.setMessageCount(conversation.getMessageCount() + 2);
    if (conversation.getTitle() == null) {
      String title = text.replaceAll("\\s+", " ").trim();
      conversation.setTitle(title.length() <= 60 ? title : title.substring(0, 59) + "…");
    }
    return new TurnRef(job.getId(), conversation.getId(), user.getId(), reply.getId(), false);
  }

  /**
   * A turn nobody typed: something happened that Jizza should report on (an approval answered).
   * The conversation shows {@code note} as an event line, then Jizza's reply streams under it.
   */
  @Transactional
  public TurnRef beginContinuation(UUID userId, UUID conversationId, String origin, UUID planId,
                                   String note, JsonNode noteData, String title) {
    ConversationEntity conversation = conversationId == null ? create(userId) : owned(userId, conversationId);
    if (conversation.getTitle() == null) conversation.setTitle(JobSummaries.title(title));
    ChatJobEntity job = new ChatJobEntity();
    job.setUserId(userId);
    job.setConversationId(conversation.getId());
    job.setOrigin(origin);
    job.setPlanId(planId);
    job.setStatus(ChatJobEntity.RUNNING);
    job.setTitle(JobSummaries.title(title));
    try {
      job = jobs.saveAndFlush(job);
    } catch (DataIntegrityViolationException e) {
      throw new ConflictException("Jizza is answering in this conversation right now");
    }
    MessageEntity event = message(conversation.getId(), MessageRole.EVENT, note, MessageEntity.COMPLETE, job.getId());
    event.setMetadata(noteData);
    event = messages.save(event);
    MessageEntity reply = messages.save(
        message(conversation.getId(), MessageRole.ASSISTANT, "", MessageEntity.STREAMING, job.getId()));
    job.setUserMessageId(event.getId());
    job.setAssistantMessageId(reply.getId());
    conversation.setLastMessageAt(Instant.now());
    conversation.setMessageCount(conversation.getMessageCount() + 2);
    return new TurnRef(job.getId(), conversation.getId(), event.getId(), reply.getId(), false);
  }

  /** A job that was waiting on the user, now that they answered. */
  @Transactional
  public void settle(UUID jobId) {
    jobs.findById(jobId).filter(j -> ChatJobEntity.AWAITING_USER.equals(j.getStatus()))
        .ifPresent(j -> j.setStatus(ChatJobEntity.COMPLETED));
  }

  /** The turns before this one, oldest first: what Jizza is reminded of. */
  @Transactional(readOnly = true)
  public List<Msg> history(UUID conversationId, UUID userMessageId) {
    ConversationEntity conversation = conversations.findById(conversationId).orElse(null);
    String summary = conversation == null ? null : conversation.getSummary();
    Instant after = conversation == null || conversation.getSummaryThrough() == null
        ? Instant.EPOCH : conversation.getSummaryThrough();
    List<MessageEntity> recent = messages.findByConversationIdAndCreatedAtAfterOrderByCreatedAtDesc(
        conversationId, after, PageRequest.of(0, props.getContext().getMaxMessages()));
    return ContextWindow.build(summary, recent, userMessageId, props.getContext().getMaxHistoryTokens());
  }

  @Transactional
  public void finish(UUID jobId, String status, String text, JsonNode metadata, String summary,
                     Orchestrator.Turn turn) {
    ChatJobEntity job = jobs.findById(jobId).orElseThrow(() -> new NotFoundException("Job", jobId));
    job.setStatus(status);
    job.setSummary(summary);
    job.setFinishedAt(Instant.now());
    if (turn != null) {
      job.setInputTokens(turn.inputTokens());
      job.setOutputTokens(turn.outputTokens());
    }
    if (job.getAssistantMessageId() != null) {
      messages.findById(job.getAssistantMessageId()).ifPresent(reply -> {
        reply.setContent(text == null ? "" : text);
        reply.setStatus(switch (status) {
          case ChatJobEntity.COMPLETED, ChatJobEntity.AWAITING_USER -> MessageEntity.COMPLETE;
          case ChatJobEntity.CANCELLED -> MessageEntity.CANCELLED;
          default -> MessageEntity.FAILED;
        });
        if (metadata != null) reply.setMetadata(metadata);
      });
      if (turn != null) {
        for (Orchestrator.Delegation d : turn.delegations()) {
          DelegationTraceEntity trace = new DelegationTraceEntity();
          trace.setMessageId(job.getAssistantMessageId());
          trace.setFromAgent(d.fromAgentId());
          trace.setToAgent(d.agentId());
          trace.setTask(d.task());
          trace.setResult(d.result());
          traces.save(trace);
        }
      }
    }
    if (job.getConversationId() != null) {
      conversations.findById(job.getConversationId())
          .ifPresent(c -> c.setLastMessageAt(Instant.now()));
    }
  }

  @Transactional(readOnly = true)
  public String content(UUID messageId) {
    return messageId == null ? "" : messages.findById(messageId).map(MessageEntity::getContent).orElse("");
  }

  @Transactional(readOnly = true)
  public ChatJobEntity job(UUID userId, UUID jobId) {
    ChatJobEntity job = jobs.findById(jobId).orElseThrow(() -> new NotFoundException("Job", jobId));
    if (!job.getUserId().equals(userId)) throw new NotFoundException("Job", jobId);
    return job;
  }

  @Transactional(readOnly = true)
  public List<ChatJobEntity> jobs(UUID userId, UUID conversationId) {
    owned(userId, conversationId);
    return jobs.findByConversationIdOrderByStartedAtAsc(conversationId);
  }

  private ConversationEntity create(UUID userId) {
    ConversationEntity created = new ConversationEntity();
    created.setUserId(userId);
    return conversations.save(created);
  }

  private ConversationEntity owned(UUID userId, UUID conversationId) {
    ConversationEntity existing = conversations.findById(conversationId)
        .orElseThrow(() -> new NotFoundException("Conversation", conversationId));
    if (!existing.getUserId().equals(userId)) throw new NotFoundException("Conversation", conversationId);
    return existing;
  }

  private static MessageEntity message(UUID conversationId, MessageRole role, String content,
                                       String status, UUID jobId) {
    MessageEntity entity = new MessageEntity();
    entity.setConversationId(conversationId);
    entity.setRole(role);
    entity.setContent(content);
    entity.setStatus(status);
    entity.setJobId(jobId);
    return entity;
  }
}

package com.jizza.engine.chat.turn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.chat.ChatRuns;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import com.jizza.engine.persistence.entity.MessageEntity;
import com.jizza.engine.persistence.repository.ConversationRepository;
import com.jizza.engine.persistence.repository.MessageRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts the run a chat start card offers, through the workflow library so the workflow's own
 * limits still apply (turned off, runs per day, its inputs' rules), and marks the card with it.
 */
@Service
public class ChatRunStarter implements ChatRuns {

  /** The start card's action type, as the planner writes it. */
  static final String START_RUN = "start_run";
  /** How far back in a conversation a card is looked for. */
  private static final int LOOK_BACK = 200;

  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final ObjectProvider<WorkflowLibrary> library;
  private final TurnStore store;

  public ChatRunStarter(ConversationRepository conversations, MessageRepository messages,
                        ObjectProvider<WorkflowLibrary> library, TurnStore store) {
    this.conversations = conversations;
    this.messages = messages;
    this.library = library;
    this.store = store;
  }

  @Override
  @Transactional
  public RunDto start(UUID userId, UUID conversationId, UUID planId, UUID workflowId,
                      JsonNode inputs, UUID browserId) {
    Card card = card(userId, conversationId, planId);
    if (card.payload().hasNonNull("runId")) {
      throw new ConflictException("This card has already started a run");
    }
    if (!card.payload().path("workflowId").asText("").equals(String.valueOf(workflowId))) {
      throw new ValidationException("This card is for a different workflow");
    }
    WorkflowLibrary lib = library.getIfAvailable();
    if (lib == null) throw new ValidationException("Workflows are not available here");
    RunDto run = lib.reuse(userId, workflowId, cleaned(inputs), WorkflowLibrary.Use.CHAT,
        "chat:" + conversationId, true, browserId);
    card.payload().put("runId", run.id().toString()).put("startedAt", Instant.now().toString());
    if (browserId != null) card.payload().put("browserId", browserId.toString());
    card.payload().remove("dismissed");
    card.save(messages);
    store.note(conversationId, "Started " + card.payload().path("name").asText("the workflow") + ".",
        Json.obj().put("runId", run.id().toString()));
    return run;
  }

  @Override
  @Transactional
  public void dismiss(UUID userId, UUID conversationId, UUID planId) {
    Card card = card(userId, conversationId, planId);
    if (card.payload().hasNonNull("runId")) return;
    card.payload().put("dismissed", true);
    card.save(messages);
  }

  /** The values with empty ones left out, so an input asked during the run is not sent as "". */
  static JsonNode cleaned(JsonNode inputs) {
    ObjectNode out = Json.obj();
    if (inputs == null || !inputs.isObject()) return out;
    inputs.fields().forEachRemaining(e -> {
      JsonNode v = e.getValue();
      boolean empty = v == null || v.isNull() || v.isTextual() && v.asText().isBlank()
          || v.isArray() && v.isEmpty();
      if (!empty) out.set(e.getKey(), v);
    });
    return out;
  }

  /** A start card on one of Jizza's replies, with the reply's metadata copied for saving back. */
  private record Card(MessageEntity message, ObjectNode metadata, ObjectNode payload) {
    void save(MessageRepository messages) {
      message.setMetadata(metadata);
      messages.save(message);
    }
  }

  private Card card(UUID userId, UUID conversationId, UUID planId) {
    conversations.findById(conversationId).filter(c -> userId.equals(c.getUserId()))
        .orElseThrow(() -> new NotFoundException("Conversation", conversationId));
    for (MessageEntity m : messages.findByConversationIdOrderByCreatedAtDesc(conversationId,
        PageRequest.of(0, LOOK_BACK))) {
      JsonNode meta = m.getMetadata();
      if (meta == null || !meta.path("actions").isArray()) continue;
      ObjectNode copy = meta.deepCopy();
      for (JsonNode action : copy.path("actions")) {
        if (START_RUN.equals(action.path("type").asText())
            && planId.toString().equals(action.path("payload").path("planId").asText())
            && action.path("payload") instanceof ObjectNode payload) {
          return new Card(m, copy, payload);
        }
      }
    }
    throw new NotFoundException("Start card", planId);
  }
}

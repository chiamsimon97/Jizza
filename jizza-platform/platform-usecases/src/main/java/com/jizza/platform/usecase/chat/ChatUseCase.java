package com.jizza.platform.usecase.chat;

import com.jizza.engine.api.chat.ChatService;
import com.jizza.engine.api.chat.JobDto;
import com.jizza.engine.api.chat.TurnRef;
import com.jizza.engine.api.chat.TurnSubmission;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.AssistantReply;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Sending messages to Jizza (R18). There is deliberately nothing here that addresses a specialist:
 * every message goes to Jizza, and every reply comes back from Jizza.
 */
@Service
public class ChatUseCase {

  /** Long enough to paste a document's worth of context; short enough to keep a turn affordable. */
  static final int MAX_MESSAGE_CHARS = 16_000;

  private final ChatService chat;

  public ChatUseCase(ChatService chat) {
    this.chat = chat;
  }

  /** Starts a turn and returns at once; the reply arrives as events on the user's socket. */
  public TurnRef send(UUID userId, TurnSubmission submission) {
    return chat.submit(userId, checked(submission));
  }

  /** Starts a turn and waits for its reply. */
  public AssistantReply sendAndWait(UUID userId, TurnSubmission submission) {
    return chat.submitAndWait(userId, checked(submission));
  }

  public JobDto job(UUID userId, UUID jobId, int afterSeq) {
    return chat.job(userId, jobId, Math.max(0, afterSeq));
  }

  public void cancel(UUID userId, UUID jobId) {
    chat.cancel(userId, jobId);
  }

  private static TurnSubmission checked(TurnSubmission s) {
    if (s.text() == null || s.text().isBlank()) throw new ValidationException("Type a message first");
    if (s.text().length() > MAX_MESSAGE_CHARS) {
      throw new ValidationException("That message is too long. Keep it under "
          + MAX_MESSAGE_CHARS + " characters, or save the document to an agent's memory page.");
    }
    if (s.incognito() && s.conversationId() != null) {
      throw new ValidationException("An incognito message cannot go into a saved conversation");
    }
    String screen = s.screen() == null || s.screen().isBlank() ? null : s.screen().trim();
    return new TurnSubmission(s.conversationId(), s.clientMessageId(), s.text(), screen, s.incognito());
  }
}

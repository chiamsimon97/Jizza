package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.chat.JobDto;
import com.jizza.engine.api.chat.TurnRef;
import com.jizza.engine.api.chat.TurnSubmission;
import com.jizza.engine.api.orchestrator.AssistantReply;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.usecase.chat.ChatUseCase;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The user's single point of contact (R18). There is deliberately no endpoint that addresses a
 * specialist: every message goes to Jizza, and every reply comes back from Jizza.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/chat")
public class ChatController {

  private final ChatUseCase chat;

  public ChatController(ChatUseCase chat) {
    this.chat = chat;
  }

  /**
   * Send a message and wait for the reply. Pass {@code incognito=true} for a temporary turn: the
   * message and its reply are not stored, no audit entry is created, and the reply carries a null
   * conversationId. Without a conversationId, a new conversation is started.
   */
  @PostMapping
  public AssistantReply chat(@Valid @RequestBody Requests.Chat request,
                             @RequestParam(defaultValue = "false") boolean incognito) {
    return chat.sendAndWait(CurrentUser.userId(), new TurnSubmission(
        incognito ? null : request.conversationId(), null, request.message(), request.screen(), incognito));
  }

  /**
   * Send a message and return at once. Everything the turn does arrives on
   * {@code /user/queue/chat}; the reply is its {@code job.completed} event.
   */
  @PostMapping("/turns")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TurnRef send(@Valid @RequestBody Requests.Turn request) {
    boolean incognito = Boolean.TRUE.equals(request.incognito());
    return chat.send(CurrentUser.userId(), new TurnSubmission(request.conversationId(),
        request.clientMessageId(), request.message(), request.screen(), incognito, request.context()));
  }

  /** A job and its events after {@code afterSeq}, for catching up after a reconnect or reopening one. */
  @GetMapping("/jobs/{jobId}")
  public JobDto job(@PathVariable UUID jobId, @RequestParam(defaultValue = "0") int afterSeq) {
    return chat.job(CurrentUser.userId(), jobId, afterSeq);
  }

  @PostMapping("/jobs/{jobId}/cancel")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void cancel(@PathVariable UUID jobId) {
    chat.cancel(CurrentUser.userId(), jobId);
  }
}

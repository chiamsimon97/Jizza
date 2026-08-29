package com.jizza.web.api;

import com.jizza.common.orchestrator.AssistantReply;
import com.jizza.common.orchestrator.Orchestrator;
import com.jizza.security.session.CurrentUser;
import com.jizza.web.api.dto.Requests;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The user's single point of contact (R18). There is deliberately no endpoint that addresses a
 * specialist: every message goes to Jizza, and every reply comes back from Jizza.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

  private final Orchestrator orchestrator;

  public ChatController(Orchestrator orchestrator) {
    this.orchestrator = orchestrator;
  }

  @PostMapping
  public AssistantReply chat(@Valid @RequestBody Requests.Chat request) {
    return orchestrator.handle(CurrentUser.userId(), request.conversationId(), request.message());
  }
}

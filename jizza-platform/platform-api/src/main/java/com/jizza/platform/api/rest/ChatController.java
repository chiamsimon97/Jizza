package com.jizza.platform.api.rest;

import com.jizza.engine.api.orchestrator.AssistantReply;
import com.jizza.engine.api.orchestrator.Orchestrator;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Requests;
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

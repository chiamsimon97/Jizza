package com.jizza.platform.api.rest;

import com.jizza.engine.api.dto.ConversationDto;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.chat.ConversationUseCase;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view over the user's conversation history. */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

  private final ConversationUseCase conversations;

  public ConversationController(ConversationUseCase conversations) {
    this.conversations = conversations;
  }

  @GetMapping
  public List<ConversationDto> list(@RequestParam(defaultValue = "50") int limit) {
    return conversations.list(CurrentUser.userId(), limit);
  }

  @GetMapping("/{id}")
  public ConversationDto get(@PathVariable UUID id) {
    return conversations.get(CurrentUser.userId(), id);
  }

  @GetMapping("/{id}/messages")
  public List<ConversationDto.MessageDto> messages(@PathVariable UUID id) {
    return conversations.messages(CurrentUser.userId(), id);
  }
}

package com.jizza.platform.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.chat.ChatRunUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The start card on Jizza's reply when the person asks to run one of their saved workflows in
 * their own words: Start runs it with the values and browser on the card; No thanks closes it.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/chat/runs")
public class ChatRunController {

  private final ChatRunUseCase runs;

  public ChatRunController(ChatRunUseCase runs) {
    this.runs = runs;
  }

  public record Start(@NotNull UUID conversationId, @NotNull UUID planId, @NotNull UUID workflowId,
                      JsonNode inputs, UUID browserId) {}

  public record Dismiss(@NotNull UUID conversationId, @NotNull UUID planId) {}

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RunDto start(@Valid @RequestBody Start body) {
    return runs.start(CurrentUser.userId(), body.conversationId(), body.planId(), body.workflowId(),
        body.inputs(), body.browserId());
  }

  @PostMapping("/dismiss")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void dismiss(@Valid @RequestBody Dismiss body) {
    runs.dismiss(CurrentUser.userId(), body.conversationId(), body.planId());
  }
}

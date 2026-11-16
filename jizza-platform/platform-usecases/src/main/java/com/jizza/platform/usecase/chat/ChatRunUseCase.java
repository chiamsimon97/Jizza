package com.jizza.platform.usecase.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.chat.ChatRuns;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.error.ValidationException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Pressing Start (or No thanks) on the card Jizza shows when the person asks to run a workflow. */
@Service
public class ChatRunUseCase {

  /** Enough for a form's worth of values and a few dozen rows. */
  static final int MAX_INPUT_CHARS = 32_000;

  private final ChatRuns runs;

  public ChatRunUseCase(ChatRuns runs) {
    this.runs = runs;
  }

  public RunDto start(UUID userId, UUID conversationId, UUID planId, UUID workflowId,
                      JsonNode inputs, UUID browserId) {
    if (inputs != null && !inputs.isNull() && !inputs.isObject()) {
      throw new ValidationException("The values must be named fields");
    }
    if (inputs != null && inputs.toString().length() > MAX_INPUT_CHARS) {
      throw new ValidationException("Those values are too long to start a run with");
    }
    return runs.start(userId, conversationId, planId, workflowId, inputs, browserId);
  }

  public void dismiss(UUID userId, UUID conversationId, UUID planId) {
    runs.dismiss(userId, conversationId, planId);
  }
}

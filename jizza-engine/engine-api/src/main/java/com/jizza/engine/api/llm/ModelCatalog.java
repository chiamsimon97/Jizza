package com.jizza.engine.api.llm;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The models a person can pick: the platform's own, and those their connected providers offer.
 * Each says what it can do, so a chat is only offered a model that can handle it.
 */
public interface ModelCatalog {

  /**
   * One model the person can use.
   *
   * @param ref            what is sent as the model (see {@link ModelRefs})
   * @param provider       {@code anthropic} for the platform's models too
   * @param ownKey         true when the person's own key pays for it
   * @param tools          can call tools (without them it can only talk)
   * @param inputPerMillion  price of a million input tokens in US dollars, when known
   */
  record ModelInfo(String ref, String provider, String label, String note, boolean ownKey,
                   boolean tools, boolean vision, boolean pdf, Integer contextTokens,
                   BigDecimal inputPerMillion, BigDecimal outputPerMillion) {}

  /** Every model the person may pick, platform models first, hidden ones left out. */
  List<ModelInfo> available(UUID userId);

  /** What is known about one model; empty for a reference nobody recognises. */
  Optional<ModelInfo> find(UUID userId, String ref);

  /** A name to show for a model, such as "GPT-5.1 · your OpenAI key"; the reference itself if unknown. */
  default String label(UUID userId, String ref) {
    return find(userId, ref).map(ModelInfo::label).orElse(ref);
  }
}

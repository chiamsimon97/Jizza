package com.jizza.engine.api.llm;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The AI providers a person connects with their own key (F1), as the settings page manages them.
 * Keys go in and never come back out: a connection shows only the key's last four characters.
 */
public interface ModelProviderSettings {

  /** A provider the person can connect, and what it needs. */
  record ProviderKind(String provider, String name, String note, boolean needsKey,
                      boolean needsAddress, String defaultBaseUrl, String keyLink) {}

  /** One model a connection offers, as the settings page lists it. */
  record ProviderModel(String id, String ref, String label, boolean tools, boolean vision,
                       boolean pdf, Integer contextTokens, BigDecimal inputPerMillion,
                       BigDecimal outputPerMillion, boolean hidden) {}

  /** A connection, without its key. */
  record ProviderView(UUID id, String provider, String name, String baseUrl, String keyHint,
                      boolean enabled, Instant modelsRefreshedAt, String lastError,
                      List<ProviderModel> models) {}

  /** What the person changed about one model: hidden from the picker, its prices, what it can do. */
  record ModelPatch(Boolean hidden, BigDecimal inputPerMillion, BigDecimal outputPerMillion,
                    Boolean tools, Boolean vision, Boolean pdf) {}

  List<ProviderKind> kinds();

  List<ProviderView> list(UUID userId);

  /** Connects a provider or replaces its key and address; checks the key first. Blank keeps the key. */
  ProviderView connect(UUID userId, String provider, String baseUrl, String apiKey);

  ProviderView refresh(UUID userId, String provider);

  void remove(UUID userId, String provider);

  ProviderView setEnabled(UUID userId, String provider, boolean enabled);

  ProviderView updateModel(UUID userId, String provider, String modelId, ModelPatch patch);
}

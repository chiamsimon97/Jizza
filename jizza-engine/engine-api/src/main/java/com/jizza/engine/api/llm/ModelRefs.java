package com.jizza.engine.api.llm;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * How a model is named across the platform. A bare model id ({@code claude-sonnet-5-5}) is one the
 * platform runs on its own Anthropic key, as before. A model reached with the person's own key is
 * named {@code <provider>:<model id>}, for example {@code openai:gpt-5.1},
 * {@code openrouter:meta-llama/llama-4-maverick} or {@code ollama:llama3.3:70b} (only the first colon
 * separates, since local model ids carry colons of their own).
 */
public final class ModelRefs {

  public static final String ANTHROPIC = "anthropic";
  public static final String OPENAI = "openai";
  public static final String OPENROUTER = "openrouter";
  public static final String GEMINI = "gemini";
  public static final String OLLAMA = "ollama";
  /** Any other server that speaks the OpenAI chat completions format, at an address the person gives. */
  public static final String CUSTOM = "custom";

  /** Every provider a person can connect, in the order they are offered. */
  public static final List<String> PROVIDERS = List.of(OPENAI, ANTHROPIC, GEMINI, OPENROUTER, OLLAMA, CUSTOM);

  private ModelRefs() {}

  /** A model on one provider, reached with the person's own key. */
  public record Ref(String provider, String modelId) {
    public String value() { return provider + ":" + modelId; }
  }

  /** The provider and model of a reference to the person's own key; empty for a platform model. */
  public static Optional<Ref> parse(String ref) {
    if (ref == null) return Optional.empty();
    int colon = ref.indexOf(':');
    if (colon <= 0 || colon == ref.length() - 1) return Optional.empty();
    String provider = ref.substring(0, colon).toLowerCase(Locale.ROOT);
    if (!PROVIDERS.contains(provider)) return Optional.empty();
    return Optional.of(new Ref(provider, ref.substring(colon + 1)));
  }

  /** True when the model runs on the person's own key rather than the platform's. */
  public static boolean ownKey(String ref) {
    return parse(ref).isPresent();
  }

  public static String of(String provider, String modelId) {
    return provider.toLowerCase(Locale.ROOT) + ":" + modelId;
  }
}

package com.jizza.engine.knowledge.embedding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which embedding model indexes knowledge and memory.
 *
 * <p>{@code local} (the default) is the offline hashing provider: nothing leaves the machine, but
 * similarity is lexical. {@code openai-compatible} calls any {@code /v1/embeddings} endpoint for
 * genuinely semantic vectors; pointed at a local Ollama ({@code http://localhost:11434/v1}) it keeps
 * the local-first promise too.
 */
@ConfigurationProperties(prefix = "jizza.embedding")
public class EmbeddingProperties {

  private String provider = "local";
  private final OpenAiCompatible openaiCompatible = new OpenAiCompatible();

  public static class OpenAiCompatible {
    private String baseUrl = "http://localhost:11434/v1";
    private String apiKey;
    private String model = "nomic-embed-text";
    /** Sent as {@code dimensions} when set, for models that can shorten their output. */
    private Integer dimensions;
    private Duration timeout = Duration.ofSeconds(30);

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public Integer getDimensions() { return dimensions; }
    public void setDimensions(Integer v) { this.dimensions = v; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration v) { this.timeout = v; }
  }

  public String getProvider() { return provider; }
  public void setProvider(String v) { this.provider = v; }
  public OpenAiCompatible getOpenaiCompatible() { return openaiCompatible; }
}

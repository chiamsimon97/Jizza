package com.jizza.engine.agents.config;

import com.jizza.engine.api.enums.ModelTier;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent runtime configuration. Model strings are configuration, never literals, so the routing
 * model can stay cheap and the hard-task model can change without a code edit (R49).
 */
@ConfigurationProperties(prefix = "jizza.agents")
public class AgentProperties {

  private final Anthropic anthropic = new Anthropic();
  private final Loop loop = new Loop();
  private final Budget budget = new Budget();
  private final Teams teams = new Teams();

  /** Model string per tier, e.g. {@code routing: claude-haiku-...}. */
  private Map<ModelTier, String> models = new EnumMap<>(ModelTier.class);

  /** Cost per million tokens, keyed by model string, used for budgets and reporting. */
  private Map<String, ModelPrice> pricing = new HashMap<>();

  public static class Anthropic {
    private String baseUrl = "https://api.anthropic.com";
    private String apiKey;
    private String version = "2023-06-01";
    private Duration timeout = Duration.ofSeconds(120);
    private int maxOutputTokens = 4096;
    private final Retry retry = new Retry();
    private final Caching caching = new Caching();

    public Retry getRetry() { return retry; }
    public Caching getCaching() { return caching; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v; }
    public String getVersion() { return version; }
    public void setVersion(String v) { this.version = v; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration v) { this.timeout = v; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int v) { this.maxOutputTokens = v; }
  }

  /**
   * How a model call that failed for a passing reason is tried again: rate limited, overloaded, a
   * provider error or a dropped connection. A request the provider rejected is never repeated.
   */
  public static class Retry {
    /** Attempts in all, the first one included; 1 turns retrying off. */
    private int maxAttempts = 4;
    /** Wait before the first retry; each retry after doubles it, with jitter. */
    private Duration initialBackoff = Duration.ofSeconds(1);
    /** No computed wait is longer than this. */
    private Duration maxBackoff = Duration.ofSeconds(20);
    /** A provider asking to wait longer than this ends the call rather than holding the turn. */
    private Duration maxRetryAfter = Duration.ofSeconds(60);

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int v) { this.maxAttempts = v; }
    public Duration getInitialBackoff() { return initialBackoff; }
    public void setInitialBackoff(Duration v) { this.initialBackoff = v; }
    public Duration getMaxBackoff() { return maxBackoff; }
    public void setMaxBackoff(Duration v) { this.maxBackoff = v; }
    public Duration getMaxRetryAfter() { return maxRetryAfter; }
    public void setMaxRetryAfter(Duration v) { this.maxRetryAfter = v; }
  }

  /**
   * Prompt caching. The tool definitions and an agent's own instructions are the same on every
   * call, so they are marked for the provider to keep; inside a tool loop the growing conversation
   * is cached too, because the next round resends all of it.
   */
  public static class Caching {
    private boolean enabled = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
  }

  public static class Loop {
    /** Hard cap on tool-use rounds in a single turn, so a confused model cannot spin (R16). */
    private int maxToolIterations = 8;
    /** Cap on tokens across one run. */
    private int maxTokensPerRun = 120_000;
    /** Truncation limit applied to any tool output before it re-enters the model context. */
    private int maxToolResultChars = 20_000;

    public int getMaxToolIterations() { return maxToolIterations; }
    public void setMaxToolIterations(int v) { this.maxToolIterations = v; }
    public int getMaxTokensPerRun() { return maxTokensPerRun; }
    public void setMaxTokensPerRun(int v) { this.maxTokensPerRun = v; }
    public int getMaxToolResultChars() { return maxToolResultChars; }
    public void setMaxToolResultChars(int v) { this.maxToolResultChars = v; }
  }

  /** Limits on one request as it travels down a team, so a deep team cannot run away. */
  public static class Teams {
    /** Agent runs one request may start across the whole team, the first one included. */
    private int maxRunsPerRequest = 20;
    /** Tokens one request may spend across the whole team. */
    private int maxTokensPerRequest = 600_000;
    /** Levels below the first agent a hand-off may reach. */
    private int maxDepth = 4;
    /** Reports a manager works with at once when it hands work to several. */
    private int maxParallel = 4;

    public int getMaxRunsPerRequest() { return maxRunsPerRequest; }
    public void setMaxRunsPerRequest(int v) { this.maxRunsPerRequest = v; }
    public int getMaxTokensPerRequest() { return maxTokensPerRequest; }
    public void setMaxTokensPerRequest(int v) { this.maxTokensPerRequest = v; }
    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int v) { this.maxDepth = v; }
    public int getMaxParallel() { return maxParallel; }
    public void setMaxParallel(int v) { this.maxParallel = v; }
  }

  public static class Budget {
    /** Spend ceiling per rolling window; zero disables the ceiling. */
    private double perWindowUnits = 5.0;
    private Duration window = Duration.ofDays(1);

    public double getPerWindowUnits() { return perWindowUnits; }
    public void setPerWindowUnits(double v) { this.perWindowUnits = v; }
    public Duration getWindow() { return window; }
    public void setWindow(Duration v) { this.window = v; }
  }

  public static class ModelPrice {
    private double inputPerMillion;
    private double outputPerMillion;
    /** Price of a cache read as a share of the input price. */
    private double cacheReadMultiplier = 0.1;
    /** Price of a cache write as a share of the input price (the five-minute cache). */
    private double cacheWriteMultiplier = 1.25;

    public double getCacheReadMultiplier() { return cacheReadMultiplier; }
    public void setCacheReadMultiplier(double v) { this.cacheReadMultiplier = v; }
    public double getCacheWriteMultiplier() { return cacheWriteMultiplier; }
    public void setCacheWriteMultiplier(double v) { this.cacheWriteMultiplier = v; }
    public double getInputPerMillion() { return inputPerMillion; }
    public void setInputPerMillion(double v) { this.inputPerMillion = v; }
    public double getOutputPerMillion() { return outputPerMillion; }
    public void setOutputPerMillion(double v) { this.outputPerMillion = v; }
  }

  public Anthropic getAnthropic() { return anthropic; }
  public Loop getLoop() { return loop; }
  public Teams getTeams() { return teams; }
  public Budget getBudget() { return budget; }
  public Map<ModelTier, String> getModels() { return models; }
  public void setModels(Map<ModelTier, String> v) { this.models = v; }
  public Map<String, ModelPrice> getPricing() { return pricing; }
  public void setPricing(Map<String, ModelPrice> v) { this.pricing = v; }
}

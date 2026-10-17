package com.jizza.engine.chat.config;

import com.jizza.engine.api.enums.ModelTier;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code jizza.chat.*}: how much of a conversation the model sees, and its upkeep. */
@ConfigurationProperties("jizza.chat")
public class ChatProperties {

  private final Context context = new Context();
  private final Upkeep summaries = new Upkeep();
  private final Upkeep titles = new Upkeep();

  public Context getContext() { return context; }
  public Upkeep getSummaries() { return summaries; }
  public Upkeep getTitles() { return titles; }

  public static class Context {
    /** Estimated tokens of prior messages replayed to the model each turn. */
    private int maxHistoryTokens = 12_000;
    /** A hard cap on how many prior messages are read, whatever their size. */
    private int maxMessages = 200;

    public int getMaxHistoryTokens() { return maxHistoryTokens; }
    public void setMaxHistoryTokens(int maxHistoryTokens) { this.maxHistoryTokens = maxHistoryTokens; }
    public int getMaxMessages() { return maxMessages; }
    public void setMaxMessages(int maxMessages) { this.maxMessages = maxMessages; }
  }

  /** A small model task run after a turn, off the user's path. */
  public static class Upkeep {
    private boolean enabled = true;
    private ModelTier tier = ModelTier.ROUTING;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public ModelTier getTier() { return tier; }
    public void setTier(ModelTier tier) { this.tier = tier; }
  }
}

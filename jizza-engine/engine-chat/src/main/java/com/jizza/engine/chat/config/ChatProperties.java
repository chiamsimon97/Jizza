package com.jizza.engine.chat.config;

import com.jizza.engine.api.enums.ModelTier;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code jizza.chat.*}: how much of a conversation the model sees, and its upkeep. */
@ConfigurationProperties("jizza.chat")
public class ChatProperties {

  private final Context context = new Context();
  private final Upkeep summaries = new Upkeep();
  private final Upkeep titles = new Upkeep();
  private final Threads threads = new Threads();

  public Context getContext() { return context; }
  public Upkeep getSummaries() { return summaries; }
  public Upkeep getTitles() { return titles; }
  public Threads getThreads() { return threads; }

  /** Threads: topics Jizza moves out of the main chat to a specialist, each with a guide. */
  public static class Threads {
    /** Off: Jizza answers everything in the main chat, as before threads existed. */
    private boolean enabled = true;
    /** Keeping each thread's guide up to date after a turn. */
    private final Upkeep guides = new Upkeep();
    /** Jizza deciding, before each message is answered, which thread it belongs to. */
    private final Upkeep router = new Upkeep();
    /** The longest a guide may grow; Jizza condenses it to stay under this. */
    private int maxGuideChars = 6_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Upkeep getGuides() { return guides; }
    public Upkeep getRouter() { return router; }
    public int getMaxGuideChars() { return maxGuideChars; }
    public void setMaxGuideChars(int maxGuideChars) { this.maxGuideChars = maxGuideChars; }
  }

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

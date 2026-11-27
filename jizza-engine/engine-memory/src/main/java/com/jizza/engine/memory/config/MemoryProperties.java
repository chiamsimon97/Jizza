package com.jizza.engine.memory.config;

import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.enums.ModelTier;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hybrid memory configuration ({@code jizza.memory.*}). Thresholds, weights and budgets are all
 * here rather than in code, because what "relevant enough" means depends on the embedding model.
 */
@ConfigurationProperties(prefix = "jizza.memory")
public class MemoryProperties {

  /** Master switch for handing memory to agents. Off leaves storage and the API working. */
  private boolean enabled = true;

  /**
   * Which agent domains see a category by default. A category not listed is shared with every
   * agent. A fact can still name its own domains explicitly.
   */
  private Map<MemoryCategory, List<String>> categoryDomains = defaultCategoryDomains();

  /**
   * Whether Jizza (the orchestrator) sees domain-restricted facts too. Off by default: Jizza gets
   * the shared layer (who the people are, preferences), which is enough to delegate; the specialist
   * it delegates to gets the domain detail.
   */
  private boolean orchestratorSeesAllDomains = false;

  private final Retrieval retrieval = new Retrieval();
  private final Write write = new Write();
  private final Extraction extraction = new Extraction();
  private final Retention retention = new Retention();
  private final Access access = new Access();
  private final Review review = new Review();

  private static Map<MemoryCategory, List<String>> defaultCategoryDomains() {
    Map<MemoryCategory, List<String>> map = new EnumMap<>(MemoryCategory.class);
    map.put(MemoryCategory.FINANCE, List.of("finance"));
    map.put(MemoryCategory.HEALTH, List.of("health"));
    map.put(MemoryCategory.PROFESSIONAL, List.of("professional"));
    return map;
  }

  public static class Retrieval {
    /** Most facts placed in one agent turn. */
    private int maxItems = 12;
    /** Character budget for the rendered memory block, so memory never crowds out the task. */
    private int maxChars = 4000;
    /** How many candidates each signal (semantic, keyword, subject) contributes before ranking. */
    private int candidateLimit = 30;
    /** Vector hits below this cosine similarity are not candidates at all. */
    private double minSimilarity = 0.2;
    /** Ranked score a fact needs to be included. */
    private double minScore = 0.35;
    /** Facts at or above this importance are always included (the core profile). */
    private double pinnedImportance = 0.9;
    private int pinnedLimit = 5;
    /** Age at which the recency signal halves. */
    private Duration recencyHalfLife = Duration.ofDays(90);
    /** How far to follow subject relations from a subject named in the request. */
    private int relationDepth = 1;
    private final Weights weights = new Weights();

    public int getMaxItems() { return maxItems; }
    public void setMaxItems(int v) { this.maxItems = v; }
    public int getMaxChars() { return maxChars; }
    public void setMaxChars(int v) { this.maxChars = v; }
    public int getCandidateLimit() { return candidateLimit; }
    public void setCandidateLimit(int v) { this.candidateLimit = v; }
    public double getMinSimilarity() { return minSimilarity; }
    public void setMinSimilarity(double v) { this.minSimilarity = v; }
    public double getMinScore() { return minScore; }
    public void setMinScore(double v) { this.minScore = v; }
    public double getPinnedImportance() { return pinnedImportance; }
    public void setPinnedImportance(double v) { this.pinnedImportance = v; }
    public int getPinnedLimit() { return pinnedLimit; }
    public void setPinnedLimit(int v) { this.pinnedLimit = v; }
    public Duration getRecencyHalfLife() { return recencyHalfLife; }
    public void setRecencyHalfLife(Duration v) { this.recencyHalfLife = v; }
    public int getRelationDepth() { return relationDepth; }
    public void setRelationDepth(int v) { this.relationDepth = v; }
    public Weights getWeights() { return weights; }
  }

  /** Weights of each ranking signal; each signal is in [0, 1]. */
  public static class Weights {
    private double similarity = 0.45;
    private double subject = 0.35;
    private double recency = 0.10;
    private double confidence = 0.05;
    private double importance = 0.05;
    /** Similarity credited to a keyword hit that has no vector score. */
    private double keywordHit = 0.5;
    /** Subject signal for a subject reached through a relation rather than named directly. */
    private double relatedSubject = 0.6;

    public double getSimilarity() { return similarity; }
    public void setSimilarity(double v) { this.similarity = v; }
    public double getSubject() { return subject; }
    public void setSubject(double v) { this.subject = v; }
    public double getRecency() { return recency; }
    public void setRecency(double v) { this.recency = v; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double v) { this.confidence = v; }
    public double getImportance() { return importance; }
    public void setImportance(double v) { this.importance = v; }
    public double getKeywordHit() { return keywordHit; }
    public void setKeywordHit(double v) { this.keywordHit = v; }
    public double getRelatedSubject() { return relatedSubject; }
    public void setRelatedSubject(double v) { this.relatedSubject = v; }
  }

  public static class Write {
    /** Cosine similarity at which a new fact counts as one already known. */
    private double duplicateSimilarity = 0.95;
    private int maxContentChars = 2000;
    private double defaultImportance = 0.5;
    /** Confidence given to facts an agent records on the user's behalf. */
    private double agentConfidence = 0.9;

    public double getDuplicateSimilarity() { return duplicateSimilarity; }
    public void setDuplicateSimilarity(double v) { this.duplicateSimilarity = v; }
    public int getMaxContentChars() { return maxContentChars; }
    public void setMaxContentChars(int v) { this.maxContentChars = v; }
    public double getDefaultImportance() { return defaultImportance; }
    public void setDefaultImportance(double v) { this.defaultImportance = v; }
    public double getAgentConfidence() { return agentConfidence; }
    public void setAgentConfidence(double v) { this.agentConfidence = v; }
  }

  public static class Extraction {
    /** Learning from conversations at all; each user can still switch it off for themselves. */
    private boolean enabled = true;
    /** Extraction is a cheap classification job, so it runs on the routing model. */
    private ModelTier tier = ModelTier.ROUTING;
    /** Facts the model is less sure of than this are not promoted. */
    private double minConfidence = 0.7;
    /** Turns shorter than this ("ok", "thanks") are not worth a model call. */
    private int minMessageChars = 12;
    private int maxFactsPerTurn = 8;
    /** Off the request thread by default; tests switch it off to assert on the result. */
    private boolean async = true;

    public boolean isAsync() { return async; }
    public void setAsync(boolean v) { this.async = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public ModelTier getTier() { return tier; }
    public void setTier(ModelTier v) { this.tier = v; }
    public double getMinConfidence() { return minConfidence; }
    public void setMinConfidence(double v) { this.minConfidence = v; }
    public int getMinMessageChars() { return minMessageChars; }
    public void setMinMessageChars(int v) { this.minMessageChars = v; }
    public int getMaxFactsPerTurn() { return maxFactsPerTurn; }
    public void setMaxFactsPerTurn(int v) { this.maxFactsPerTurn = v; }
  }

  /**
   * The memory review ({@code jizza.memory.review.*}): once a night, reads each active user's
   * recent chats and failures and suggests memory changes for them to approve.
   */
  public static class Review {
    /** The nightly review at all; each user can still switch it off for themselves. */
    private boolean enabled = true;
    /** When the nightly review runs, in {@link #zone}. */
    private String cron = "0 0 3 * * *";
    private String zone = "Asia/Kolkata";
    /** Reading a day of activity well is worth the standard model, once a night. */
    private ModelTier tier = ModelTier.STANDARD;
    /** The longest stretch one review reads; older activity is left alone. */
    private Duration maxWindow = Duration.ofDays(7);
    /** A nightly review skips a user reviewed more recently than this (a manual one counts). */
    private Duration minGap = Duration.ofHours(20);
    /** "Review now" can't be pressed more often than this. */
    private Duration manualCooldown = Duration.ofMinutes(10);
    /** No new review while this many suggestions are waiting: they would pile up unread. */
    private int maxOpen = 20;
    private int maxProposalsPerRun = 8;
    /** How much of the user's own words the review reads. */
    private int maxMessages = 60;
    private int maxMessageChars = 400;
    private int maxFailures = 60;
    private int maxFacts = 80;
    /** Facts at least this similar to a recently changed one are shown beside it as possible repeats. */
    private double neighbourSimilarity = 0.75;
    /** Unanswered suggestions expire after this. */
    private Duration expireAfter = Duration.ofDays(30);
    /** A dismissed suggestion is not raised again for this long. */
    private Duration dismissedMemory = Duration.ofDays(90);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getCron() { return cron; }
    public void setCron(String v) { this.cron = v; }
    public String getZone() { return zone; }
    public void setZone(String v) { this.zone = v; }
    public ModelTier getTier() { return tier; }
    public void setTier(ModelTier v) { this.tier = v; }
    public Duration getMaxWindow() { return maxWindow; }
    public void setMaxWindow(Duration v) { this.maxWindow = v; }
    public Duration getMinGap() { return minGap; }
    public void setMinGap(Duration v) { this.minGap = v; }
    public Duration getManualCooldown() { return manualCooldown; }
    public void setManualCooldown(Duration v) { this.manualCooldown = v; }
    public int getMaxOpen() { return maxOpen; }
    public void setMaxOpen(int v) { this.maxOpen = v; }
    public int getMaxProposalsPerRun() { return maxProposalsPerRun; }
    public void setMaxProposalsPerRun(int v) { this.maxProposalsPerRun = v; }
    public int getMaxMessages() { return maxMessages; }
    public void setMaxMessages(int v) { this.maxMessages = v; }
    public int getMaxMessageChars() { return maxMessageChars; }
    public void setMaxMessageChars(int v) { this.maxMessageChars = v; }
    public int getMaxFailures() { return maxFailures; }
    public void setMaxFailures(int v) { this.maxFailures = v; }
    public int getMaxFacts() { return maxFacts; }
    public void setMaxFacts(int v) { this.maxFacts = v; }
    public double getNeighbourSimilarity() { return neighbourSimilarity; }
    public void setNeighbourSimilarity(double v) { this.neighbourSimilarity = v; }
    public Duration getExpireAfter() { return expireAfter; }
    public void setExpireAfter(Duration v) { this.expireAfter = v; }
    public Duration getDismissedMemory() { return dismissedMemory; }
    public void setDismissedMemory(Duration v) { this.dismissedMemory = v; }
  }

  public static class Retention {
    /** Superseded and retired facts are kept this long for history, then deleted. */
    private Duration inactive = Duration.ofDays(180);
    /** Re-embedding batch size when the embedding provider changes. */
    private int reembedBatch = 200;

    public Duration getInactive() { return inactive; }
    public void setInactive(Duration v) { this.inactive = v; }
    public int getReembedBatch() { return reembedBatch; }
    public void setReembedBatch(int v) { this.reembedBatch = v; }
  }

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean v) { this.enabled = v; }
  public Map<MemoryCategory, List<String>> getCategoryDomains() { return categoryDomains; }
  public void setCategoryDomains(Map<MemoryCategory, List<String>> v) { this.categoryDomains = v; }
  public boolean isOrchestratorSeesAllDomains() { return orchestratorSeesAllDomains; }
  public void setOrchestratorSeesAllDomains(boolean v) { this.orchestratorSeesAllDomains = v; }
  public Retrieval getRetrieval() { return retrieval; }
  public Write getWrite() { return write; }
  public Extraction getExtraction() { return extraction; }
  public Retention getRetention() { return retention; }
  public Review getReview() { return review; }
  public Access getAccess() { return access; }

  /** Read stamps: which agent was handed which memory, once per run. */
  public static class Access {
    /** Write stamps off the request thread, so an agent's read never waits on them. */
    private boolean async = true;

    public boolean isAsync() { return async; }
    public void setAsync(boolean async) { this.async = async; }
  }
}

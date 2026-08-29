package com.jizza.platform.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jizza.platform")
public class PlatformProperties {

  private final Queue queue = new Queue();
  private final Storage storage = new Storage();
  private final Mail mail = new Mail();
  private final Files files = new Files();
  private final Retention retention = new Retention();

  public static class Queue {
    private boolean enabled = true;
    private int batchSize = 10;
    private int maxAttempts = 5;
    private Duration leaseDuration = Duration.ofMinutes(5);
    private Duration pollInterval = Duration.ofSeconds(2);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int v) { this.batchSize = v; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int v) { this.maxAttempts = v; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration v) { this.leaseDuration = v; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration v) { this.pollInterval = v; }
  }

  public static class Storage {
    /** Local-first object storage root. Swap the provider, not this path, to move to S3. */
    private String root = "./data/storage";
    public String getRoot() { return root; }
    public void setRoot(String v) { this.root = v; }
  }

  public static class Mail {
    private String from = "jizza@localhost";
    private boolean enabled = true;
    public String getFrom() { return from; }
    public void setFrom(String v) { this.from = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
  }

  public static class Files {
    private long maxBytes = 8L * 1024 * 1024;
    private List<String> allowedContentTypes = List.of(
        "application/json", "application/yaml", "application/x-yaml", "text/yaml",
        "text/plain", "text/markdown", "application/pdf");
    public long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(long v) { this.maxBytes = v; }
    public List<String> getAllowedContentTypes() { return allowedContentTypes; }
    public void setAllowedContentTypes(List<String> v) { this.allowedContentTypes = v; }
  }

  public static class Retention {
    /** Conversation history older than this is pruned; zero disables pruning. */
    private Duration conversationHistory = Duration.ofDays(365);
    public Duration getConversationHistory() { return conversationHistory; }
    public void setConversationHistory(Duration v) { this.conversationHistory = v; }
  }

  public Queue getQueue() { return queue; }
  public Storage getStorage() { return storage; }
  public Mail getMail() { return mail; }
  public Files getFiles() { return files; }
  public Retention getRetention() { return retention; }
}

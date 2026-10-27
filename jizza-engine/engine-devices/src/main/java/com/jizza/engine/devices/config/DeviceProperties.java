package com.jizza.engine.devices.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code jizza.devices.*}: the agent gateway's timings and limits. */
@ConfigurationProperties(prefix = "jizza.devices")
public class DeviceProperties {

  /** How often devices send a heartbeat; three missed ones mark a device offline. */
  private Duration heartbeat = Duration.ofSeconds(30);
  /** How long an access token from a challenge sign-in lasts. */
  private Duration tokenTtl = Duration.ofMinutes(15);
  private Duration pairingTtl = Duration.ofMinutes(10);
  private Duration challengeTtl = Duration.ofMinutes(1);
  /** The longest a long poll is held open. */
  private Duration maxPollWait = Duration.ofSeconds(25);
  /** How long a run waits for a device that is asking a person for help (a login, a captcha). */
  private Duration needsUserGrace = Duration.ofMinutes(10);
  /** The longest time limit a command may be given. */
  private Duration maxCommandTimeout = Duration.ofHours(24);
  private int maxQueuedPerDevice = 100;
  private int maxCommandsPerMinutePerWorkspace = 600;
  /** Largest protocol message accepted, in bytes. */
  private int maxMessageBytes = 256 * 1024;
  private long maxFileBytes = 25L * 1024 * 1024;
  private Duration logRetention = Duration.ofDays(7);
  /** Enrollment and sign-in attempts allowed per address per minute. */
  private int authAttemptsPerMinute = 20;
  /** Protocol major versions this server speaks, newest last. */
  private int[] protocolVersions = {1};

  public Duration getHeartbeat() { return heartbeat; }
  public void setHeartbeat(Duration heartbeat) { this.heartbeat = heartbeat; }
  public Duration getTokenTtl() { return tokenTtl; }
  public void setTokenTtl(Duration tokenTtl) { this.tokenTtl = tokenTtl; }
  public Duration getPairingTtl() { return pairingTtl; }
  public void setPairingTtl(Duration pairingTtl) { this.pairingTtl = pairingTtl; }
  public Duration getChallengeTtl() { return challengeTtl; }
  public void setChallengeTtl(Duration challengeTtl) { this.challengeTtl = challengeTtl; }
  public Duration getMaxPollWait() { return maxPollWait; }
  public void setMaxPollWait(Duration maxPollWait) { this.maxPollWait = maxPollWait; }
  public Duration getNeedsUserGrace() { return needsUserGrace; }
  public void setNeedsUserGrace(Duration needsUserGrace) { this.needsUserGrace = needsUserGrace; }
  public Duration getMaxCommandTimeout() { return maxCommandTimeout; }
  public void setMaxCommandTimeout(Duration maxCommandTimeout) { this.maxCommandTimeout = maxCommandTimeout; }
  public int getMaxQueuedPerDevice() { return maxQueuedPerDevice; }
  public void setMaxQueuedPerDevice(int maxQueuedPerDevice) { this.maxQueuedPerDevice = maxQueuedPerDevice; }
  public int getMaxCommandsPerMinutePerWorkspace() { return maxCommandsPerMinutePerWorkspace; }
  public void setMaxCommandsPerMinutePerWorkspace(int value) { this.maxCommandsPerMinutePerWorkspace = value; }
  public int getMaxMessageBytes() { return maxMessageBytes; }
  public void setMaxMessageBytes(int maxMessageBytes) { this.maxMessageBytes = maxMessageBytes; }
  public long getMaxFileBytes() { return maxFileBytes; }
  public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
  public Duration getLogRetention() { return logRetention; }
  public void setLogRetention(Duration logRetention) { this.logRetention = logRetention; }
  public int getAuthAttemptsPerMinute() { return authAttemptsPerMinute; }
  public void setAuthAttemptsPerMinute(int authAttemptsPerMinute) { this.authAttemptsPerMinute = authAttemptsPerMinute; }
  public int[] getProtocolVersions() { return protocolVersions; }
  public void setProtocolVersions(int[] protocolVersions) { this.protocolVersions = protocolVersions; }

  public int currentProtocol() {
    return protocolVersions[protocolVersions.length - 1];
  }
}

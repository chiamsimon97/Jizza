package com.jizza.engine.scheduler.config;

import com.jizza.engine.api.enums.CatchUpPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jizza.scheduler")
public class SchedulerProperties {

  /** The Charter's home zone; every task carries its own, this is only the default. */
  private String defaultTimezone = "Asia/Kolkata";

  private CatchUpPolicy defaultCatchUpPolicy = CatchUpPolicy.RUN_ONCE;

  /** How often the dispatcher looks for due work. */
  private Duration pollInterval = Duration.ofSeconds(20);

  /** A run that started longer ago than this is presumed dead and is reclaimed on boot. */
  private Duration staleRunAfter = Duration.ofMinutes(30);

  /** How long an unattended task waits for a permission answer before giving up for this run. */
  private Duration permissionWait = Duration.ofMinutes(2);

  private boolean enabled = true;

  public String getDefaultTimezone() { return defaultTimezone; }
  public void setDefaultTimezone(String v) { this.defaultTimezone = v; }
  public CatchUpPolicy getDefaultCatchUpPolicy() { return defaultCatchUpPolicy; }
  public void setDefaultCatchUpPolicy(CatchUpPolicy v) { this.defaultCatchUpPolicy = v; }
  public Duration getPollInterval() { return pollInterval; }
  public void setPollInterval(Duration v) { this.pollInterval = v; }
  public Duration getStaleRunAfter() { return staleRunAfter; }
  public void setStaleRunAfter(Duration v) { this.staleRunAfter = v; }
  public Duration getPermissionWait() { return permissionWait; }
  public void setPermissionWait(Duration v) { this.permissionWait = v; }
  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean v) { this.enabled = v; }
}

package com.jizza.agents.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.autonomy.AutonomyGate;
import com.jizza.common.autonomy.Decision;
import com.jizza.common.autonomy.KillSwitch;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The agent runtime is built before the autonomy module exists, so it wires by interface. These
 * fallbacks keep the application startable in that window and are replaced the moment
 * {@code jizza-autonomy} contributes the real beans -- autoconfiguration is evaluated last, so a
 * component-scanned implementation always wins.
 *
 * <p>They log a warning on every use: an unguarded runtime is a development state, not a shipping
 * one.
 */
@AutoConfiguration
public class AutonomyFallbackAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(AutonomyFallbackAutoConfiguration.class);

  @Bean
  @ConditionalOnMissingBean(AutonomyGate.class)
  public AutonomyGate permissiveAutonomyGate() {
    log.warn("No AutonomyGate implementation found; falling back to a permissive gate. "
        + "jizza-autonomy must be on the classpath before any autonomous action is allowed.");
    return new AutonomyGate() {
      @Override public Decision evaluate(UUID agentId, String toolRef, JsonNode args) {
        return Decision.allow("fallback-permissive");
      }

      @Override public boolean agentEnabled(UUID agentId) { return true; }
    };
  }

  @Bean
  @ConditionalOnMissingBean(KillSwitch.class)
  public KillSwitch inactiveKillSwitch() {
    log.warn("No KillSwitch implementation found; falling back to a no-op switch.");
    return new KillSwitch() {
      @Override public boolean paused(UUID userId) { return false; }

      @Override public void setPaused(UUID userId, boolean paused) {
        throw new UnsupportedOperationException("The kill switch requires the jizza-autonomy module");
      }

      @Override public void requireActive(UUID userId) { /* nothing to enforce */ }
    };
  }
}

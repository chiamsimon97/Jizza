package com.jizza.platform.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableAsync
@EnableCaching
@EnableConfigurationProperties(PlatformProperties.class)
public class PlatformConfig {

  /**
   * In-process Caffeine caching (R46), behind Spring's {@code CacheManager} so swapping in Redis
   * later is a configuration change rather than a code change.
   *
   * <p>Deliberately short-lived and small: caching anything derived from health or financial data
   * for long is a liability, not an optimisation.
   */
  @Bean
  public CacheManager cacheManager() {
    CaffeineCacheManager manager = new CaffeineCacheManager(
        "agents", "capabilities", "generatedTools", "embeddings", "rateLimits");
    manager.setCaffeine(Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(5))
        .recordStats());
    return manager;
  }
}

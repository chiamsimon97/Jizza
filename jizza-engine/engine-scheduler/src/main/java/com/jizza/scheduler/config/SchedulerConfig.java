package com.jizza.scheduler.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ShedLock over the {@code shedlock} table gives the distributed lock that stops two worker
 * processes firing the same task twice (R30). The lock lives in PostgreSQL, so it works without any
 * additional infrastructure.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
@EnableConfigurationProperties(SchedulerProperties.class)
public class SchedulerConfig {

  @Bean
  @ConditionalOnMissingBean(LockProvider.class)
  public LockProvider lockProvider(DataSource dataSource) {
    return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
        .withTableName("shedlock")
        .usingDbTime()
        .build());
  }
}

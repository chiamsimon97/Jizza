package com.jizza.platform.api.jobs;

import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.platform.usecase.finance.review.AlertService;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Kai Portfolio's Phase 1a jobs (5.15), in India time, one user at a time, each safe to run
 * twice: the daily alerts at 08:00 and the monthly snapshot, health check and review on the 1st
 * at 06:00. One user's failure never stops the others, and the log names only the user id.
 */
@Component
@ConditionalOnProperty(name = "jizza.finance.enabled", havingValue = "true")
public class FinanceJobs {

  private static final Logger log = LoggerFactory.getLogger(FinanceJobs.class);

  private final FinanceRecords records;
  private final AlertService alerts;

  public FinanceJobs(FinanceRecords records, AlertService alerts) {
    this.records = records;
    this.alerts = alerts;
  }

  @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Kolkata")
  @SchedulerLock(name = "jizza-fin-alerts", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
  public void dailyAlerts() {
    int users = 0;
    int sent = 0;
    for (UUID owner : records.ownersWithProfile()) {
      try {
        sent += alerts.sendAlerts(owner);
        users++;
      } catch (RuntimeException e) {
        log.warn("Finance alerts failed for user {}: {}", owner, e.getClass().getSimpleName());
      }
    }
    log.info("Finance alerts: {} users checked, {} alerts raised", users, sent);
  }

  @Scheduled(cron = "0 0 6 1 * *", zone = "Asia/Kolkata")
  @SchedulerLock(name = "jizza-fin-monthly", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
  public void monthly() {
    int users = 0;
    for (UUID owner : records.ownersWithProfile()) {
      try {
        alerts.monthly(owner);
        users++;
      } catch (RuntimeException e) {
        log.warn("Finance monthly run failed for user {}: {}", owner, e.getClass().getSimpleName());
      }
    }
    log.info("Finance monthly run: {} users", users);
  }
}

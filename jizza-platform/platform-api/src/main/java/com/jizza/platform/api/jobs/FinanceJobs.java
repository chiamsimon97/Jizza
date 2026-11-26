package com.jizza.platform.api.jobs;

import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.platform.usecase.finance.goal.GoalCheckService;
import com.jizza.platform.usecase.finance.health.FinHealthUseCase;
import com.jizza.platform.usecase.finance.ledger.EarmarkService;
import com.jizza.platform.usecase.finance.market.PriceService;
import com.jizza.platform.usecase.finance.review.AlertService;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Kai Portfolio's jobs (5.15), in India time, one user at a time, each safe to run twice: fund
 * prices from AMFI at 23:30 (shared, not per user), the nightly earmark check, health check and goal check at
 * 00:30, the daily alerts at 08:00 and the monthly snapshot, health check and review on the 1st
 * at 06:00. One user's failure never stops the others, and the log names only the user id.
 */
@Component
@ConditionalOnProperty(name = "jizza.finance.enabled", havingValue = "true")
public class FinanceJobs {

  private static final Logger log = LoggerFactory.getLogger(FinanceJobs.class);

  private final FinanceRecords records;
  private final AlertService alerts;
  private final PriceService prices;
  private final EarmarkService earmarks;
  private final FinHealthUseCase health;
  private final GoalCheckService goalChecks;

  public FinanceJobs(FinanceRecords records, AlertService alerts, PriceService prices, EarmarkService earmarks,
                     FinHealthUseCase health, GoalCheckService goalChecks) {
    this.records = records;
    this.alerts = alerts;
    this.prices = prices;
    this.earmarks = earmarks;
    this.health = health;
    this.goalChecks = goalChecks;
  }

  @Scheduled(cron = "0 30 23 * * *", zone = "Asia/Kolkata")
  @SchedulerLock(name = "jizza-fin-prices", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
  public void prices() {
    try {
      PriceService.Refresh r = prices.refreshNavs();
      log.info("Fund prices: {} read, {} matched, {} saved", r.fetched(), r.matched(), r.saved());
    } catch (RuntimeException e) {
      log.warn("Fund prices failed: {}", e.getClass().getSimpleName());
    }
  }

  /** Every holding's earmarks brought back to its lots (units sold outside Kai), then the health and goal checks. */
  @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Kolkata")
  @SchedulerLock(name = "jizza-fin-nightly", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
  public void nightly() {
    int users = 0;
    int changed = 0;
    for (UUID owner : records.ownersWithProfile()) {
      try {
        changed += earmarks.reconcileAll(owner);
        health.runFor(owner);
        goalChecks.runFor(owner);
        users++;
      } catch (RuntimeException e) {
        log.warn("Finance nightly run failed for user {}: {}", owner, e.getClass().getSimpleName());
      }
    }
    log.info("Finance nightly run: {} users, {} holdings reconciled", users, changed);
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

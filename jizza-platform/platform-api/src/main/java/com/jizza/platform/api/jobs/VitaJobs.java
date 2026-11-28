package com.jizza.platform.api.jobs;

import com.jizza.platform.usecase.vita.medicine.VitaReminderService;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Vita's jobs (spec 5.12), in India time and only while {@code jizza.health.enabled} is on:
 * {@code jizza-vita-reminders} every minute (due slot reminders, missed slots, the daily check,
 * other reminders, and today's dose rows if the nightly run did not make them) and
 * {@code jizza-vita-nightly} at 00:45 (end dates, 48 hours of dose rows, refills, visit
 * reminders). Both are safe to run twice. The log holds counts only.
 */
@Component
@ConditionalOnProperty(name = "jizza.health.enabled", havingValue = "true")
public class VitaJobs {

  private static final Logger log = LoggerFactory.getLogger(VitaJobs.class);

  private final VitaReminderService reminders;

  public VitaJobs(VitaReminderService reminders) {
    this.reminders = reminders;
  }

  @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
  @SchedulerLock(name = "jizza-vita-reminders", lockAtMostFor = "PT50S")
  public void reminders() {
    var run = reminders.minute(Instant.now());
    if (run.slotReminders() + run.missedDoses() + run.dailyChecks() + run.otherReminders() + run.expanded() > 0) {
      log.info("Vita reminders: {} slots reminded, {} doses missed ({} alerts), {} daily checks, {} other, {} rows made",
          run.slotReminders(), run.missedDoses(), run.missedAlerts(), run.dailyChecks(), run.otherReminders(), run.expanded());
    }
  }

  @Scheduled(cron = "0 45 0 * * *", zone = "Asia/Kolkata")
  @SchedulerLock(name = "jizza-vita-nightly", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
  public void nightly() {
    reminders.nightly(Instant.now());
  }
}

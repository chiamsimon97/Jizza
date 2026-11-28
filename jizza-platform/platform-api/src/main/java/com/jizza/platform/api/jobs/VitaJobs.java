package com.jizza.platform.api.jobs;

import com.jizza.platform.usecase.vita.documents.VitaMailPullService;
import com.jizza.platform.usecase.vita.medicine.VitaReminderService;
import java.time.Instant;
import java.util.UUID;
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
 * reminders), and {@code jizza-vita-mail} at 07:15 (reports in linked mailboxes, each filed as a
 * document to review). All are safe to run twice. The log holds counts and user ids only.
 */
@Component
@ConditionalOnProperty(name = "jizza.health.enabled", havingValue = "true")
public class VitaJobs {

  private static final Logger log = LoggerFactory.getLogger(VitaJobs.class);

  private final VitaReminderService reminders;
  private final VitaMailPullService mail;

  public VitaJobs(VitaReminderService reminders, VitaMailPullService mail) {
    this.reminders = reminders;
    this.mail = mail;
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

  /** Reports in the mailbox: each new one waits for review; nothing is saved on its own. */
  @Scheduled(cron = "0 15 7 * * *", zone = "Asia/Kolkata")
  @SchedulerLock(name = "jizza-vita-mail", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
  public void mailPull() {
    int users = 0;
    int found = 0;
    for (UUID owner : mail.owners()) {
      try {
        found += mail.checkAll(owner);
        users++;
      } catch (RuntimeException e) {
        log.warn("Vita mail check failed for user {}: {}", owner, e.getClass().getSimpleName());
      }
    }
    if (found > 0) log.info("Vita mail check: {} users, {} reports to review", users, found);
  }
}

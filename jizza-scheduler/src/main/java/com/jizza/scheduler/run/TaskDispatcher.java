package com.jizza.scheduler.run;

import com.jizza.common.enums.ScheduleType;
import com.jizza.common.enums.TaskStatus;
import com.jizza.persistence.entity.ScheduledTaskEntity;
import com.jizza.persistence.repository.ScheduledTaskRepository;
import com.jizza.scheduler.config.SchedulerProperties;
import com.jizza.scheduler.recurrence.RecurrenceCalculator;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wakes on {@code next_run_at} and runs what is due (R29/R30).
 *
 * <p>Two layers keep a task from double-firing: ShedLock means only one process polls at a time,
 * and {@link TaskRunner#claim} flips the row to {@code running} in its own committed transaction,
 * so even a lock hand-off mid-poll cannot produce two runs.
 */
@Component
public class TaskDispatcher {

  private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

  private final ScheduledTaskRepository tasks;
  private final TaskRunner runner;
  private final RecurrenceCalculator recurrence;
  private final SchedulerProperties props;

  public TaskDispatcher(ScheduledTaskRepository tasks, TaskRunner runner,
                        RecurrenceCalculator recurrence, SchedulerProperties props) {
    this.tasks = tasks;
    this.runner = runner;
    this.recurrence = recurrence;
    this.props = props;
  }

  @Scheduled(fixedDelayString = "${jizza.scheduler.poll-interval:PT20S}")
  @SchedulerLock(name = "jizza-task-dispatch", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
  public void dispatchDue() {
    if (!props.isEnabled()) return;
    for (ScheduledTaskEntity task : tasks.findDue(TaskStatus.PENDING, Instant.now())) {
      if (runner.claim(task.getId())) {
        try {
          runner.run(task.getId());
        } catch (RuntimeException e) {
          log.error("Dispatching task {} failed outright", task.getId(), e);
        }
      }
    }
  }

  /**
   * Boot reconciliation (R30): tasks left {@code running} by a process that died are returned to
   * the queue, and schedules whose window passed while the system was down are settled according to
   * the task's catch-up policy rather than all firing at once.
   */
  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void reconcileOnStartup() {
    if (!props.isEnabled()) return;

    List<ScheduledTaskEntity> stuck = tasks.findByStatus(TaskStatus.RUNNING);
    Instant staleBefore = Instant.now().minus(props.getStaleRunAfter());
    for (ScheduledTaskEntity task : stuck) {
      if (task.getLastRunAt() == null || task.getLastRunAt().isBefore(staleBefore)) {
        log.info("Reclaiming task {} left running by a previous process", task.getTitle());
        task.setStatus(TaskStatus.PENDING);
        tasks.save(task);
      }
    }

    Instant now = Instant.now();
    for (ScheduledTaskEntity task : tasks.findByStatus(TaskStatus.PENDING)) {
      Instant due = task.getNextRunAt();
      if (due == null || !due.isBefore(now)) continue;
      if (task.getScheduleType() == ScheduleType.ONE_TIME) continue;  // fires on the next poll

      var settled = recurrence.reconcileMissed(task.getCronOrRrule(), task.getTimezone(),
          task.getCreatedAt(), due, now, task.getCatchUpPolicy(), 0);
      task.setNextRunAt(settled.orElse(null));
      if (task.getNextRunAt() == null) task.setStatus(TaskStatus.DONE);
      tasks.save(task);
      log.info("Reconciled missed runs for '{}' under policy {}; next run {}", task.getTitle(),
          task.getCatchUpPolicy(), task.getNextRunAt());
    }
  }
}

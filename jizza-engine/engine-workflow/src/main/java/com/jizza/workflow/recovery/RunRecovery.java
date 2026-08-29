package com.jizza.workflow.recovery;

import com.jizza.common.autonomy.KillSwitch;
import com.jizza.common.enums.RunStatus;
import com.jizza.common.workflow.WorkflowEngine;
import com.jizza.persistence.entity.WorkflowRunEntity;
import com.jizza.persistence.repository.WorkflowRunRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Picks runs back up (R33).
 *
 * <p>Two situations look identical from the database and are handled the same way: a run left
 * {@code running} by a process that died, and a run halted mid-flight because the kill switch was
 * thrown and has since been released. Both are simply advanced again from their last completed
 * node.
 */
@Component
public class RunRecovery {

  private static final Logger log = LoggerFactory.getLogger(RunRecovery.class);

  private final WorkflowRunRepository runs;
  private final WorkflowEngine engine;
  private final KillSwitch killSwitch;

  public RunRecovery(WorkflowRunRepository runs, WorkflowEngine engine, KillSwitch killSwitch) {
    this.runs = runs;
    this.engine = engine;
    this.killSwitch = killSwitch;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void resumeOnStartup() {
    recover("startup");
  }

  @Scheduled(fixedDelayString = "${jizza.workflow.recovery-interval:PT60S}")
  @SchedulerLock(name = "jizza-run-recovery", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
  public void resumePeriodically() {
    recover("sweep");
  }

  private void recover(String source) {
    for (WorkflowRunEntity run : runs.findByStatus(RunStatus.RUNNING)) {
      if (killSwitch.paused(run.getUserId())) continue;
      try {
        log.info("Resuming run {} ({})", run.getId(), source);
        engine.resumeAfterRestart(run.getId());
      } catch (RuntimeException e) {
        log.warn("Could not resume run {}", run.getId(), e);
      }
    }
  }
}

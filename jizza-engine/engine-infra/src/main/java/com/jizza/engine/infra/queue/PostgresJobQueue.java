package com.jizza.engine.infra.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.JobStatus;
import com.jizza.engine.api.queue.JobQueue;
import com.jizza.engine.persistence.entity.JobEntity;
import com.jizza.engine.persistence.repository.JobRepository;
import com.jizza.engine.infra.config.PlatformProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The durable job queue that drives the scheduler and workflow workers (R42).
 *
 * <p>PostgreSQL rather than Redis, because local-first should need one piece of infrastructure, not
 * two. Concurrency safety comes from {@code select ... for update skip locked}: two workers polling
 * the same table simply take different rows. A worker that dies mid-job leaves its lease behind,
 * and the lease expiry hands the job to someone else rather than losing it.
 *
 * <p>Handlers must be idempotent: a job can be delivered again if a worker dies after doing the
 * work but before marking it done.
 */
@Service
public class PostgresJobQueue implements JobQueue {

  private static final Logger log = LoggerFactory.getLogger(PostgresJobQueue.class);

  private final JobRepository jobs;
  private final JobLeaser leaser;
  private final PlatformProperties props;
  private final Map<String, JobHandler> handlers = new ConcurrentHashMap<>();

  public PostgresJobQueue(JobRepository jobs, JobLeaser leaser, PlatformProperties props) {
    this.jobs = jobs;
    this.leaser = leaser;
    this.props = props;
  }

  @Override
  public UUID enqueue(UUID userId, String type, JsonNode payload) {
    return enqueue(userId, type, payload, Instant.now());
  }

  @Override
  @Transactional
  public UUID enqueue(UUID userId, String type, JsonNode payload, Instant runAfter) {
    JobEntity job = new JobEntity();
    job.setUserId(userId);
    job.setType(type);
    job.setPayload(payload);
    job.setStatus(JobStatus.READY);
    job.setRunAfter(runAfter == null ? Instant.now() : runAfter);
    job.setMaxAttempts(props.getQueue().getMaxAttempts());
    jobs.save(job);
    return job.getId();
  }

  @Override
  public void register(String type, JobHandler handler) {
    handlers.put(type, handler);
  }

  @Scheduled(fixedDelayString = "${jizza.platform.queue.poll-interval:PT2S}")
  public void poll() {
    if (!props.getQueue().isEnabled()) return;

    // Reclaim first: a job whose worker died is more urgent than a fresh one.
    reclaim();

    List<JobEntity> leased = leaser.lease(props.getQueue().getBatchSize(),
        props.getQueue().getLeaseDuration());
    for (JobEntity job : leased) {
      handle(job);
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void reclaim() {
    int reclaimed = jobs.reclaimExpiredLeases(Instant.now());
    if (reclaimed > 0) log.info("Reclaimed {} job(s) from expired leases", reclaimed);
  }

  private void handle(JobEntity job) {
    JobHandler handler = handlers.get(job.getType());
    if (handler == null) {
      log.warn("No handler registered for job type '{}'; leaving it for a worker that has one",
          job.getType());
      release(job.getId(), JobStatus.READY, "no handler registered");
      return;
    }
    try {
      handler.handle(new Job(job.getId(), job.getUserId(), job.getType(), job.getPayload(),
          job.getAttempts()));
      release(job.getId(), JobStatus.DONE, null);
    } catch (RuntimeException e) {
      log.warn("Job {} ({}) failed on attempt {}", job.getId(), job.getType(), job.getAttempts(), e);
      boolean exhausted = job.getAttempts() >= job.getMaxAttempts();
      release(job.getId(), exhausted ? JobStatus.DEAD_LETTER : JobStatus.READY,
          String.valueOf(e.getMessage()));
      if (exhausted) {
        log.error("Job {} ({}) dead-lettered after {} attempts", job.getId(), job.getType(),
            job.getAttempts());
      }
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void release(UUID jobId, JobStatus status, String error) {
    jobs.findById(jobId).ifPresent(job -> {
      job.setStatus(status);
      job.setLastError(error);
      job.setLeasedBy(null);
      job.setLeasedUntil(null);
      if (status == JobStatus.READY) {
        // Back off before the next attempt so a persistently failing job does not spin.
        long backoff = (long) Math.pow(2, Math.min(job.getAttempts(), 6));
        job.setRunAfter(Instant.now().plusSeconds(backoff));
      }
      jobs.save(job);
    });
  }
}

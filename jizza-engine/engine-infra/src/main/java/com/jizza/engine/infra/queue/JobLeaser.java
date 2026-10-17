package com.jizza.engine.infra.queue;

import com.jizza.engine.api.enums.JobStatus;
import com.jizza.engine.persistence.entity.JobEntity;
import com.jizza.engine.persistence.repository.JobRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes a batch of ready jobs for this worker.
 *
 * <p>The selection and the claim happen in one transaction: {@code for update skip locked} holds
 * the rows until commit, so a second worker polling at the same moment simply skips past them and
 * takes different ones. That is the whole of the double-processing guarantee, and it needs no
 * external lock service.
 */
@Component
public class JobLeaser {

  private static final String WORKER_ID =
      java.lang.management.ManagementFactory.getRuntimeMXBean().getName();

  @PersistenceContext
  private EntityManager em;

  private final JobRepository jobs;

  public JobLeaser(JobRepository jobs) {
    this.jobs = jobs;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @SuppressWarnings("unchecked")
  public List<JobEntity> lease(int batchSize, Duration leaseFor) {
    List<UUID> ids = em.createNativeQuery("""
            select id from job_queue
             where status = 'ready' and run_after <= now()
             order by run_after
             limit :batch
             for update skip locked
            """)
        .setParameter("batch", batchSize)
        .getResultList();
    if (ids.isEmpty()) return List.of();

    Instant until = Instant.now().plus(leaseFor);
    List<JobEntity> leased = jobs.findAllById(ids);
    for (JobEntity job : leased) {
      job.setStatus(JobStatus.LEASED);
      job.setLeasedBy(WORKER_ID);
      job.setLeasedUntil(until);
      job.setAttempts(job.getAttempts() + 1);
    }
    return jobs.saveAll(leased);
  }

  /**
   * Re-offers jobs whose lease expired because the worker died mid-flight. Lives here rather than
   * in the queue so the call crosses a bean boundary and the transaction actually applies.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int reclaimExpired() {
    return jobs.reclaimExpiredLeases(Instant.now());
  }
}

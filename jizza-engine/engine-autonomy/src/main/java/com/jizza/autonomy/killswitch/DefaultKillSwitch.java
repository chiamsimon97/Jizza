package com.jizza.autonomy.killswitch;

import com.jizza.common.audit.AuditLog;
import com.jizza.common.autonomy.KillSwitch;
import com.jizza.common.enums.ActorType;
import com.jizza.common.error.AutonomyDeniedException;
import com.jizza.common.util.Json;
import com.jizza.persistence.entity.KillSwitchEntity;
import com.jizza.persistence.repository.KillSwitchRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The global kill switch (R40).
 *
 * <p>Read from the database on every check rather than cached, so throwing it takes effect on the
 * next checkpoint in every process, not only the one that handled the request. The system is active
 * by default: a user with no row is not paused.
 */
@Service
public class DefaultKillSwitch implements KillSwitch {

  private static final Logger log = LoggerFactory.getLogger(DefaultKillSwitch.class);

  private final KillSwitchRepository repository;
  private final AuditLog audit;

  public DefaultKillSwitch(KillSwitchRepository repository, AuditLog audit) {
    this.repository = repository;
    this.audit = audit;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean paused(UUID userId) {
    return repository.findById(userId).map(KillSwitchEntity::isAutonomyPaused).orElse(false);
  }

  @Override
  @Transactional
  public void setPaused(UUID userId, boolean paused) {
    KillSwitchEntity entity = repository.findById(userId).orElseGet(() -> {
      KillSwitchEntity fresh = new KillSwitchEntity();
      fresh.setUserId(userId);
      return fresh;
    });
    entity.setAutonomyPaused(paused);
    repository.save(entity);

    log.warn("Autonomy {} for user {}", paused ? "PAUSED" : "resumed", userId);
    audit.record(userId, ActorType.USER, null,
        paused ? "autonomy.kill_switch_engaged" : "autonomy.kill_switch_released", null,
        Json.obj().put("paused", paused));
  }

  @Override
  public void requireActive(UUID userId) {
    if (paused(userId)) {
      throw new AutonomyDeniedException("Autonomy is paused. Nothing runs unattended until you "
          + "release the kill switch.");
    }
  }
}

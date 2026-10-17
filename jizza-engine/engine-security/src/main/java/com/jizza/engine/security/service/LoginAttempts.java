package com.jizza.engine.security.service;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.UserStatus;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.UserEntity;
import com.jizza.engine.persistence.repository.UserRepository;
import com.jizza.engine.security.config.SecurityProperties;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a failed sign-in, in its own transaction.
 *
 * <p>This has to commit independently, because the call that discovered the failure ends by
 * throwing: if the counter were written in that transaction it would roll back with it, and the
 * account would never lock however many times the password was guessed. Separating it is what makes
 * lockout actually work (R6).
 */
@Component
public class LoginAttempts {

  private final UserRepository users;
  private final AuditLog audit;
  private final SecurityProperties props;

  public LoginAttempts(UserRepository users, AuditLog audit, SecurityProperties props) {
    this.users = users;
    this.audit = audit;
    this.props = props;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(UUID userId) {
    UserEntity user = users.findById(userId).orElse(null);
    if (user == null) return;

    int failures = user.getFailedLogins() + 1;
    user.setFailedLogins(failures);

    if (failures >= props.getLockout().getMaxFailedAttempts()) {
      user.setStatus(UserStatus.LOCKED);
      user.setLockedUntil(Instant.now().plus(props.getLockout().getLockoutDuration()));
      users.save(user);
      audit.record(user.getId(), ActorType.SYSTEM, null, "auth.lockout", user.getEmail(),
          Json.obj().put("failedLogins", failures));
      return;
    }
    users.save(user);
    audit.record(user.getId(), ActorType.SYSTEM, null, "auth.login.failure", user.getEmail(),
        Json.obj().put("failedLogins", failures));
  }

  /** Records an outcome that must survive the caller throwing, without touching the counter. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordRefusal(UUID userId, String action, String target) {
    audit.record(userId, ActorType.SYSTEM, null, action, target, null);
  }
}

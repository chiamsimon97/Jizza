package com.jizza.engine.security.authz;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.PlatformRole;
import com.jizza.engine.persistence.entity.UserEntity;
import com.jizza.engine.persistence.repository.UserRepository;
import com.jizza.engine.security.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives the super admin role to the account named by {@code jizza.security.super-admin.email}, and
 * takes it from anyone else. This is the only way the role is ever set: no request can.
 */
@Component
public class SuperAdminBootstrapper {

  private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrapper.class);

  private final UserRepository users;
  private final SecurityProperties props;
  private final AuditLog audit;

  public SuperAdminBootstrapper(UserRepository users, SecurityProperties props, AuditLog audit) {
    this.users = users;
    this.props = props;
    this.audit = audit;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void apply() {
    String email = props.getSuperAdmin().getEmail().trim();
    for (UserEntity u : users.findAll()) {
      boolean should = !email.isEmpty() && u.getEmail().equalsIgnoreCase(email);
      boolean is = u.getPlatformRole() == PlatformRole.SUPER_ADMIN;
      if (should == is) continue;
      u.setPlatformRole(should ? PlatformRole.SUPER_ADMIN : PlatformRole.NONE);
      users.save(u);
      audit.record(u.getId(), ActorType.SYSTEM, null,
          should ? "authz.super_admin.bootstrapped" : "authz.super_admin.cleared", u.getEmail(), null);
      log.warn("{} the super admin role {} {}", should ? "Granted" : "Removed",
          should ? "to" : "from", u.getEmail());
    }
  }
}

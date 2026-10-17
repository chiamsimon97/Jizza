package com.jizza.engine.security.authz;

import com.jizza.engine.api.enums.PlatformRole;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import com.jizza.engine.persistence.entity.SessionEntity;
import com.jizza.engine.security.session.SessionService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Decides which account a session is acting in on each request. Membership is read every time,
 * never cached on the session, so a removal or suspension takes effect on the very next call: a
 * session pointing at a workspace its person no longer belongs to falls back to their personal one.
 */
@Component
public class ActiveWorkspaceResolver {

  private final WorkspaceDirectory directory;
  private final SessionService sessions;

  public ActiveWorkspaceResolver(WorkspaceDirectory directory, SessionService sessions) {
    this.directory = directory;
    this.sessions = sessions;
  }

  public UUID resolve(SessionEntity session) {
    UUID userId = session.getUserId();
    UUID active = session.getActiveWorkspaceId();
    if (active != null) {
      boolean member = directory.membership(active, userId).filter(m -> m.active()).isPresent();
      if (member) return active;
      // The super admin may act in any workspace that exists (every such request is audited).
      if (directory.platformRoleOf(userId) == PlatformRole.SUPER_ADMIN
          && directory.workspace(active).isPresent()) {
        return active;
      }
    }
    UUID personal = directory.personalWorkspaceOf(userId);
    sessions.setActiveWorkspace(session.getId(), personal);
    return personal;
  }
}

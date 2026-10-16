package com.jizza.engine.security.workspace;

import com.jizza.engine.api.event.MembershipChanged;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import com.jizza.engine.security.session.SessionService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * When someone leaves or is removed from an organization, any of their sessions acting in it move
 * to their personal account straight away. (The per-request membership check would catch it on
 * the next call anyway; this keeps the session rows honest too.)
 */
@Component
public class MembershipSessionFallback {

  private final WorkspaceDirectory directory;
  private final SessionService sessions;

  public MembershipSessionFallback(WorkspaceDirectory directory, SessionService sessions) {
    this.directory = directory;
    this.sessions = sessions;
  }

  @EventListener
  public void on(MembershipChanged event) {
    if (event.kind() == MembershipChanged.Kind.ROLE_CHANGED) return;
    sessions.moveOffWorkspace(event.userId(), event.workspaceId(),
        directory.personalWorkspaceOf(event.userId()));
  }
}

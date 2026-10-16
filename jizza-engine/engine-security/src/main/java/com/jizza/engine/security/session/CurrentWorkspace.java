package com.jizza.engine.security.session;

import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.error.AuthException;
import java.util.UUID;

/** The account the authenticated caller is acting in, as resolved for this request. */
public final class CurrentWorkspace {

  private CurrentWorkspace() {}

  public static UUID id() {
    UUID id = CurrentUser.require().activeWorkspaceId();
    if (id == null) throw new AuthException("No active account for this session");
    return id;
  }

  /** The caller and their active account together, as the use cases take them. */
  public static Access access() {
    var p = CurrentUser.require();
    if (p.activeWorkspaceId() == null) throw new AuthException("No active account for this session");
    return new Access(p.userId(), p.activeWorkspaceId());
  }
}

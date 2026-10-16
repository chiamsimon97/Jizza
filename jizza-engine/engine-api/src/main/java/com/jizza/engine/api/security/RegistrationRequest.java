package com.jizza.engine.api.security;

import com.jizza.engine.api.enums.WorkspaceType;

/**
 * A sign-up. {@code accountType} ORGANIZATION also creates an organization named
 * {@code organizationName}. {@code invitationToken} lets an invited email register while open
 * registration is closed. There is deliberately no role or platform-role field.
 */
public record RegistrationRequest(String email, String password, WorkspaceType accountType,
                                  String organizationName, String invitationToken) {

  public static RegistrationRequest individual(String email, String password) {
    return new RegistrationRequest(email, password, WorkspaceType.PERSONAL, null, null);
  }
}

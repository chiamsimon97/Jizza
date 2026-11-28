package com.jizza.engine.api.enums;

/**
 * The three plans (spec 4.4): an Individual account ({@code PERSONAL}), an Organization, and a
 * Family. A Family is an account with members, like an Organization, but has none of the
 * organization-only features.
 */
public enum WorkspaceType {
  PERSONAL, ORGANIZATION, FAMILY;

  /** Whether the account can have members, roles and invitations: an Organization or a Family. */
  public boolean hasMembers() {
    return this == ORGANIZATION || this == FAMILY;
  }
}

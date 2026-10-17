package com.jizza.engine.api.authz;

/**
 * Names of the permissions defined in {@code security/permissions.json}. Strings rather than an
 * enum, so a new permission is a JSON entry plus a constant. Startup fails if a constant here is
 * missing from the file.
 */
public final class Permissions {

  private Permissions() {}

  public static final String CONNECTOR_VIEW = "connector_view";
  public static final String CONNECTOR_CREATE = "connector_create";
  public static final String CONNECTOR_MODIFY = "connector_modify";
  public static final String CONNECTOR_DELETE = "connector_delete";
  /** Using an organization's connector actions through one's own agents, without seeing setup. */
  public static final String CONNECTOR_USE = "connector_use";

  public static final String AGENT_VIEW = "agent_view";
  public static final String AGENT_CREATE = "agent_create";
  public static final String AGENT_MODIFY = "agent_modify";
  public static final String AGENT_ARCHIVE = "agent_archive";

  public static final String AGENT_TEMPLATE_VIEW = "agent_template_view";
  public static final String AGENT_TEMPLATE_MANAGE = "agent_template_manage";
  public static final String AGENT_TEMPLATE_INSTALL = "agent_template_install";

  public static final String MEMBERS_VIEW = "members_view";
  public static final String MEMBERS_INVITE = "members_invite";
  public static final String MEMBERS_MODIFY = "members_modify";
  public static final String MEMBERS_REMOVE = "members_remove";

  public static final String GUARDRAILS_VIEW = "guardrails_view";
  public static final String GUARDRAILS_LOCK = "guardrails_lock";

  public static final java.util.List<String> ALL = java.util.List.of(
      CONNECTOR_VIEW, CONNECTOR_CREATE, CONNECTOR_MODIFY, CONNECTOR_DELETE, CONNECTOR_USE,
      AGENT_VIEW, AGENT_CREATE, AGENT_MODIFY, AGENT_ARCHIVE,
      AGENT_TEMPLATE_VIEW, AGENT_TEMPLATE_MANAGE, AGENT_TEMPLATE_INSTALL,
      MEMBERS_VIEW, MEMBERS_INVITE, MEMBERS_MODIFY, MEMBERS_REMOVE,
      GUARDRAILS_VIEW, GUARDRAILS_LOCK);
}

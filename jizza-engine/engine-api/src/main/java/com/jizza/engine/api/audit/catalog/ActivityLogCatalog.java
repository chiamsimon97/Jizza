package com.jizza.engine.api.audit.catalog;

import java.util.List;

/**
 * The one place a module declares how its audit actions show up in the activity view: which
 * category and sub-group an action belongs to, what to call it, and how much it matters.
 *
 * <p>Adding a log type is two steps. Record it with {@code AuditLog.record(..., "area.event", ...)},
 * then describe it here -- add a line to the core catalog, or give a module its own
 * {@code @Component} implementing this interface. Every catalog bean is merged at start-up, so a
 * new module never has to touch the activity code. An action nobody describes still shows up,
 * filed by its first segment and labelled from its name, so forgetting this step loses polish,
 * not data.
 */
public interface ActivityLogCatalog {

  /** Categories this catalog introduces. A key another catalog already used is merged into it. */
  default List<ActivityCategory> categories() {
    return List.of();
  }

  /** Log types this catalog describes. */
  List<ActivityLogType> types();
}

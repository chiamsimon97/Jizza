package com.jizza.engine.api.audit.catalog;

import java.util.List;

/**
 * A top-level section of the activity view, such as "Sign-in and security" or "Workflows".
 *
 * @param key     stable id used in filters and URLs
 * @param label   what the person sees
 * @param icon    a lucide icon name the web app renders; unknown names fall back to a dot
 * @param order   lower comes first
 * @param prefixes action prefixes filed here when no log type matches, e.g. {@code "auth."}
 * @param groups  the sub-groups inside it, in display order
 */
public record ActivityCategory(String key, String label, String icon, int order,
                               List<String> prefixes, List<ActivityGroup> groups) {

  public ActivityCategory {
    prefixes = prefixes == null ? List.of() : List.copyOf(prefixes);
    groups = groups == null ? List.of() : List.copyOf(groups);
  }

  /** A sub-group inside a category, such as "Two-step verification" under security. */
  public record ActivityGroup(String key, String label) {}
}

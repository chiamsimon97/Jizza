package com.jizza.engine.api.audit.catalog;

/**
 * How one audit action, or a family of them, is presented.
 *
 * @param pattern  an exact action ({@code "auth.login.success"}) or a prefix ending in
 *                 {@code ".*"} ({@code "auth.mfa.*"}); the most specific match wins
 * @param category the {@link ActivityCategory} key it is filed under
 * @param group    the sub-group key inside that category
 * @param label    a short human label; null derives one from the action
 * @param severity how much it matters, which drives colour and the severity filter
 */
public record ActivityLogType(String pattern, String category, String group, String label,
                              Severity severity) {

  public enum Severity { INFO, NOTICE, WARNING, CRITICAL }

  public boolean isPrefix() {
    return pattern.endsWith(".*");
  }

  /** The pattern without its wildcard, with the trailing dot: {@code "auth.mfa."}. */
  public String prefix() {
    return isPrefix() ? pattern.substring(0, pattern.length() - 1) : pattern;
  }

  public boolean matches(String action) {
    return isPrefix() ? action.startsWith(prefix()) : action.equals(pattern);
  }

  public static ActivityLogType of(String pattern, String category, String group, String label) {
    return new ActivityLogType(pattern, category, group, label, Severity.INFO);
  }

  public static ActivityLogType of(String pattern, String category, String group, String label,
                                   Severity severity) {
    return new ActivityLogType(pattern, category, group, label, severity);
  }
}

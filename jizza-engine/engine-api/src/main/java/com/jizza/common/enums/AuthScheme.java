package com.jizza.common.enums;

/** Mirrors connections.auth_scheme CHECK values. */
public enum AuthScheme { OAUTH2, API_KEY, BASIC, CUSTOM, NO_AUTH;
  public String dbValue() { return name().toLowerCase(); }
  public static AuthScheme of(String v) { return valueOf(v.toUpperCase()); }
}

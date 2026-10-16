package com.jizza.engine.api.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * The caller's role in the active workspace does not grant the permission a route needs. The body
 * names the permission and its module, never the caller's role or who else holds it.
 */
public class PermissionDeniedException extends JizzaException {

  private final String permission;
  private final String module;

  public PermissionDeniedException(String permission, String module) {
    super(HttpStatus.FORBIDDEN, "permission-denied", "Permission denied",
        "Your role in this account doesn't allow this.");
    this.permission = permission;
    this.module = module;
  }

  public String permission() { return permission; }
  public String module() { return module; }

  @Override
  public ProblemDetail toProblemDetail() {
    ProblemDetail pd = super.toProblemDetail();
    if (permission != null) pd.setProperty("permission", permission);
    if (module != null) pd.setProperty("module", module);
    return pd;
  }
}

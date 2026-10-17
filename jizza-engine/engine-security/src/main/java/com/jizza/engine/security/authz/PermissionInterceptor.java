package com.jizza.engine.security.authz;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.authz.PermissionCatalog;
import com.jizza.engine.api.authz.PermissionDefinition;
import com.jizza.engine.api.authz.PermissionEvaluator;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.PermissionDeniedException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.RequiresPermission;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.engine.security.session.CurrentWorkspace;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiresPermission} on controller handlers against the caller's role in their
 * active account. Registered ahead of the step-up interceptor, so someone without the permission is
 * refused outright rather than first asked for their password.
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

  private static final Duration DENIAL_AUDIT_WINDOW = Duration.ofMinutes(1);

  private final PermissionEvaluator evaluator;
  private final PermissionCatalog catalog;
  private final AuditLog audit;
  /** Last audited denial per person and permission, so a retry loop cannot flood the chain. */
  private final Map<String, Instant> lastDenialAudit = new ConcurrentHashMap<>();

  public PermissionInterceptor(PermissionEvaluator evaluator, PermissionCatalog catalog,
                               AuditLog audit) {
    this.evaluator = evaluator;
    this.catalog = catalog;
    this.audit = audit;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) return true;
    List<RequiresPermission> required = required(method);
    if (required.isEmpty()) return true;

    UUID userId = CurrentUser.userId();
    UUID workspaceId = CurrentWorkspace.id();
    for (RequiresPermission r : required) {
      if (!evaluator.isAllowed(userId, workspaceId, r.value())) {
        String module = catalog.find(r.value()).map(PermissionDefinition::module).orElse(null);
        auditDenial(userId, workspaceId, r.value(), request);
        throw new PermissionDeniedException(r.value(), module);
      }
    }
    return true;
  }

  /** Method-level and class-level requirements together; all must pass. */
  public static List<RequiresPermission> required(HandlerMethod method) {
    List<RequiresPermission> out = new ArrayList<>(
        AnnotatedElementUtils.findMergedRepeatableAnnotations(method.getBeanType(),
            RequiresPermission.class));
    out.addAll(AnnotatedElementUtils.findMergedRepeatableAnnotations(method.getMethod(),
        RequiresPermission.class));
    return out;
  }

  private void auditDenial(UUID userId, UUID workspaceId, String permission,
                           HttpServletRequest request) {
    Instant now = Instant.now();
    String key = userId + ":" + permission;
    Instant last = lastDenialAudit.get(key);
    if (last != null && last.isAfter(now.minus(DENIAL_AUDIT_WINDOW))) return;
    lastDenialAudit.put(key, now);
    if (lastDenialAudit.size() > 10_000) lastDenialAudit.clear();
    audit.record(userId, ActorType.USER, userId, "authz.denied", permission,
        Json.obj().put("workspaceId", workspaceId.toString())
            .put("method", request.getMethod()).put("path", request.getRequestURI()));
  }
}

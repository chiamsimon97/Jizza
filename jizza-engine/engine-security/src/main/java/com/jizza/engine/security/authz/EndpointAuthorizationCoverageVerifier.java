package com.jizza.engine.security.authz;

import com.jizza.engine.api.authz.PermissionCatalog;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.PublicEndpoint;
import com.jizza.engine.api.util.RequiresPermission;
import com.jizza.engine.security.config.SecurityConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Deny by default, checked at startup: every Jizza controller handler must say who may call it,
 * with {@link RequiresPermission}, {@link AuthenticatedOnly} or {@link PublicEndpoint}, on the
 * method or its class. A handler without one stops the app from starting, as does a permission name
 * that is not defined, or a public handler the security chain would still refuse (and the reverse),
 * so the two lists of public paths cannot drift apart.
 *
 * <p>Outside this check: actuator endpoints (their own mapping, secured by the filter chain) and
 * STOMP {@code @MessageMapping} handlers (authenticated at the WebSocket handshake).
 */
@Component
public class EndpointAuthorizationCoverageVerifier implements SmartInitializingSingleton {

  private static final String OWN_PACKAGE = "com.jizza.";

  private final ListableBeanFactory beans;
  private final PermissionCatalog catalog;

  public EndpointAuthorizationCoverageVerifier(ListableBeanFactory beans, PermissionCatalog catalog) {
    this.beans = beans;
    this.catalog = catalog;
  }

  @Override
  public void afterSingletonsInstantiated() {
    Map<String, RequestMappingHandlerMapping> mappings =
        beans.getBeansOfType(RequestMappingHandlerMapping.class);
    List<String> problems = new ArrayList<>();
    Set<String> publicPatternsUsed = new TreeSet<>();
    for (RequestMappingHandlerMapping mapping : mappings.values()) {
      for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
        HandlerMethod method = e.getValue();
        if (!method.getBeanType().getName().startsWith(OWN_PACKAGE)) continue;
        problems.addAll(check(method, e.getKey().getPatternValues(), publicPatternsUsed));
      }
    }
    for (String pattern : SecurityConfig.PUBLIC_ENDPOINTS) {
      if (pattern.startsWith("/api/") && !publicPatternsUsed.contains(pattern)
          && !mappings.isEmpty()) {
        problems.add("public path " + pattern + " in SecurityConfig has no @PublicEndpoint handler");
      }
    }
    if (!problems.isEmpty()) {
      throw new IllegalStateException("Endpoint authorization is incomplete:\n  - "
          + String.join("\n  - ", problems));
    }
  }

  /** Visible for tests: the problems with one handler. */
  List<String> check(HandlerMethod method, Set<String> paths, Set<String> publicPatternsUsed) {
    List<String> problems = new ArrayList<>();
    String where = method.getBeanType().getSimpleName() + "." + method.getMethod().getName()
        + " " + paths;
    List<RequiresPermission> permissions = PermissionInterceptor.required(method);
    boolean authenticated = has(method, AuthenticatedOnly.class);
    boolean isPublic = has(method, PublicEndpoint.class);

    int kinds = (permissions.isEmpty() ? 0 : 1) + (authenticated ? 1 : 0) + (isPublic ? 1 : 0);
    if (kinds == 0) {
      problems.add(where + " has no @RequiresPermission, @AuthenticatedOnly or @PublicEndpoint");
    } else if (isPublic && kinds > 1) {
      problems.add(where + " is @PublicEndpoint but also requires a session");
    }
    for (RequiresPermission r : permissions) {
      if (catalog.find(r.value()).isEmpty()) {
        problems.add(where + " requires '" + r.value() + "', which permissions.json does not define");
      }
    }
    if (isPublic) {
      for (String path : paths) {
        String match = publicPattern(path);
        if (match == null) {
          problems.add(where + " is @PublicEndpoint but SecurityConfig.PUBLIC_ENDPOINTS does not "
              + "list " + path);
        } else {
          publicPatternsUsed.add(match);
        }
      }
    } else {
      for (String path : paths) {
        String match = publicPattern(path);
        if (match != null) {
          problems.add(where + " requires a session but " + match + " is public in SecurityConfig");
        }
      }
    }
    return problems;
  }

  private static String publicPattern(String path) {
    AntPathMatcher matcher = new AntPathMatcher();
    for (String pattern : SecurityConfig.PUBLIC_ENDPOINTS) {
      // Path variables ({token}) must match the public pattern's wildcards.
      if (matcher.match(pattern, path.replaceAll("\\{[^/]+}", "x"))) return pattern;
    }
    return null;
  }

  private static boolean has(HandlerMethod method,
                             Class<? extends java.lang.annotation.Annotation> type) {
    return AnnotatedElementUtils.hasAnnotation(method.getMethod(), type)
        || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), type);
  }
}

package com.jizza.engine.security.stepup;

import com.jizza.engine.api.error.StepUpRequiredException;
import com.jizza.engine.api.util.StepUpRequired;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.engine.security.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces step-up re-authentication on routes marked {@link StepUpRequired} (R4): connecting a
 * service, changing autonomy or security settings, and approving high-impact work.
 *
 * <p>Freshness is read from the session row on each request rather than from anything the client
 * sends, so a step-up cannot be replayed or forged.
 */
@Component
public class StepUpInterceptor implements HandlerInterceptor {

  private final SessionService sessions;

  public StepUpInterceptor(SessionService sessions) {
    this.sessions = sessions;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) return true;
    boolean required = method.hasMethodAnnotation(StepUpRequired.class)
        || method.getBeanType().isAnnotationPresent(StepUpRequired.class);
    if (!required) return true;

    if (!sessions.stepUpFresh(CurrentUser.sessionId())) {
      throw new StepUpRequiredException();
    }
    return true;
  }
}

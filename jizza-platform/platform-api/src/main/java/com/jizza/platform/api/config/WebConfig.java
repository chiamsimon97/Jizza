package com.jizza.platform.api.config;

import com.jizza.engine.security.authz.PermissionInterceptor;
import com.jizza.engine.security.stepup.StepUpInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the permission check, then the step-up check, across the API. Permission comes first so
 * someone without the role is told so instead of being asked to re-authenticate for nothing.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final PermissionInterceptor permissions;
  private final StepUpInterceptor stepUp;

  public WebConfig(PermissionInterceptor permissions, StepUpInterceptor stepUp) {
    this.permissions = permissions;
    this.stepUp = stepUp;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(permissions).addPathPatterns("/api/**");
    registry.addInterceptor(stepUp).addPathPatterns("/api/**");
  }
}

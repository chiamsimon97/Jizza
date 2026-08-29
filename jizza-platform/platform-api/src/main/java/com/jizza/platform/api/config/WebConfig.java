package com.jizza.platform.api.config;

import com.jizza.engine.security.stepup.StepUpInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the step-up interceptor across the API so a route marked
 * {@link com.jizza.engine.api.util.StepUpRequired} is enforced wherever it appears (R4).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final StepUpInterceptor stepUp;

  public WebConfig(StepUpInterceptor stepUp) {
    this.stepUp = stepUp;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(stepUp).addPathPatterns("/api/**");
  }
}

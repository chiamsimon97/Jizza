package com.jizza.web.config;

import com.jizza.security.stepup.StepUpInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the step-up interceptor across the API so a route marked
 * {@link com.jizza.common.util.StepUpRequired} is enforced wherever it appears (R4).
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

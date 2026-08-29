package com.jizza.common.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method or class as high-risk, forcing a fresh step-up re-authentication before
 * it runs (R4): connecting a service, changing autonomy/security settings, high-impact approvals.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface StepUpRequired {}

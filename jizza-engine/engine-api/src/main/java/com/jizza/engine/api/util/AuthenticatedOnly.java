package com.jizza.engine.api.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Any signed-in user, acting on their own data: no workspace permission is checked. Every handler
 * must carry this, {@link RequiresPermission} or {@link PublicEndpoint}, or the app will not start.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface AuthenticatedOnly {}

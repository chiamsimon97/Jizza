package com.jizza.engine.api.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The permission a controller handler needs in the caller's active workspace. The value is a
 * permission name from {@code security/permissions.json} (see
 * {@link com.jizza.engine.api.authz.Permissions}). Repeat it to require several; all must pass.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(RequiresPermission.List.class)
public @interface RequiresPermission {

  String value();

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.METHOD, ElementType.TYPE})
  @interface List {
    RequiresPermission[] value();
  }
}

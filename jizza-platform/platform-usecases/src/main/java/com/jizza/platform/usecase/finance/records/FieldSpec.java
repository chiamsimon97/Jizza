package com.jizza.platform.usecase.finance.records;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One field a finance form may set: its camelCase name, what it must hold and whether a new row
 * needs it. Fields that are not listed cannot be written at all.
 */
public record FieldSpec(String name, Type type, boolean required, int max, Set<String> values) {

  public enum Type {
    /** Free text up to {@code max} characters. */
    TEXT,
    /** One of {@code values}. */
    ENUM,
    /** Rupees: 0 or more, at most two decimals. */
    MONEY,
    /** 0 to 100. */
    PCT,
    /** A rate in percent a year, 0 to 1,000. */
    RATE,
    /** A plain decimal of 0 or more (grams, units). */
    DECIMAL,
    /** A whole number from 0 to {@code max}. */
    INT,
    /** yyyy-mm-dd. */
    DATE,
    BOOL,
    /** One of the owner's own people. */
    PERSON,
    /** A list of the owner's own people. */
    PERSONS,
    /** One of the owner's own accounts. */
    ACCOUNT,
    /** One of the owner's own assets. */
    ASSET,
    /** One of the owner's own stored files. */
    FILE,
    /** One of the owner's own categories. */
    CATEGORY
  }

  public static FieldSpec text(String name, int max) {
    return new FieldSpec(name, Type.TEXT, false, max, Set.of());
  }

  public static FieldSpec oneOf(String name, String... values) {
    return new FieldSpec(name, Type.ENUM, false, 0, new LinkedHashSet<>(Arrays.asList(values)));
  }

  public static FieldSpec of(String name, Type type) {
    return new FieldSpec(name, type, false, type == Type.INT ? Integer.MAX_VALUE : 0, Set.of());
  }

  public static FieldSpec integer(String name, int max) {
    return new FieldSpec(name, Type.INT, false, max, Set.of());
  }

  public FieldSpec need() {
    return new FieldSpec(name, type, true, max, values);
  }
}

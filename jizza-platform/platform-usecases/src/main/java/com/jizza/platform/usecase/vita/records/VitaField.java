package com.jizza.platform.usecase.vita.records;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One field a Vita form may set: its camelCase name, what it must hold and whether a new row needs
 * it. Fields that are not listed cannot be written at all. These are entry formats only; no
 * clinical limit is ever checked here.
 */
public record VitaField(String name, Type type, boolean required, int max, Set<String> values) {

  public enum Type {
    /** Free text up to {@code max} characters. */
    TEXT,
    /** One of {@code values}. */
    ENUM,
    /** yyyy-mm-dd, not in the future. */
    PAST_DATE,
    BOOL,
    /** A whole number from 0 to {@code max}. */
    INT,
    /** A phone number: digits with spaces, +, - and brackets, up to {@code max} characters. */
    PHONE,
    /** yyyy-mm-dd, past or future (a follow-up date, a next visit). */
    DATE,
    /** A date and time: an instant, or a local date-time in the person's zone. */
    DATETIME,
    /** The id of one of the owner's own rows ({@code doctorId} names a doctor). */
    REF
  }

  public static VitaField text(String name, int max) {
    return new VitaField(name, Type.TEXT, false, max, Set.of());
  }

  public static VitaField phone(String name) {
    return new VitaField(name, Type.PHONE, false, 30, Set.of());
  }

  public static VitaField oneOf(String name, String... values) {
    return new VitaField(name, Type.ENUM, false, 0, new LinkedHashSet<>(Arrays.asList(values)));
  }

  public static VitaField of(String name, Type type) {
    return new VitaField(name, type, false, 0, Set.of());
  }

  public static VitaField integer(String name, int max) {
    return new VitaField(name, Type.INT, false, max, Set.of());
  }

  /** The id of one of the owner's rows of {@code table}, checked by the use case. */
  public static VitaField ref(String name, String table) {
    return new VitaField(name, Type.REF, false, 0, Set.of(table));
  }

  public VitaField need() {
    return new VitaField(name, type, true, max, values);
  }
}

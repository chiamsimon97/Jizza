package com.jizza.platform.usecase.vita.sharing;

import java.util.Locale;

/** The sections of a record that can be shared one by one (spec 2.2, 4.2 {@code vita_share.section}). */
public enum VitaSection {
  PROFILE, EMERGENCY, CONDITIONS, ALLERGIES, MEDICINES, VITALS, LABS, DOCUMENTS, VISITS, VACCINATIONS, EVENTS,
  INSURANCE, JOURNAL, GOALS;

  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The section a stored key names, or null. */
  public static VitaSection of(String key) {
    if (key == null) return null;
    try {
      return valueOf(key.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}

package com.jizza.engine.api.vita;

import java.util.List;
import java.util.UUID;

/**
 * Plain storage for Vita's person-owned tables ({@code vita_profile}, contacts, conditions,
 * allergies, measurements and, in later phases, the rest). Same contract as Kai's records store:
 * every call names the owner (the account that manages the record) and another owner's row reads
 * as missing. Which fields a form may set is decided by the Vita use cases.
 */
public interface VitaRecords extends com.jizza.engine.api.records.OwnedRecords {

  /** Every Vita table, in export order. */
  List<String> tables();

  /** Owners with any Vita profile, medicine, visit or reminder, for the scheduled jobs. */
  List<UUID> ownersWithRecords();
}

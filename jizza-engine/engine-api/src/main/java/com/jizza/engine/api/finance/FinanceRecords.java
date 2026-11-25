package com.jizza.engine.api.finance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain storage for Kai Portfolio's personal tables from Phase 1a on (accounts, assets, income,
 * budget, loans, policies, health runs, actions…). Rows travel as JSON objects with camelCase
 * field names ({@code take_home_monthly} is {@code takeHomeMonthly}); money and rates are exact
 * decimals.
 *
 * <p>Every call names the owner and touches only that owner's rows: another owner's row reads as
 * missing. The store checks table and column names against the database, never the meaning of a
 * value: which fields a form may set, and what they must hold, is decided by the finance use cases.
 */
public interface FinanceRecords {

  /** The owner's rows of a table, oldest first; {@code where} holds exact matches (camelCase). */
  List<ObjectNode> list(String table, UUID userId, Map<String, Object> where);

  Optional<ObjectNode> get(String table, UUID userId, UUID id);

  /** Inserts a row for the owner and returns it as stored (with id and defaults). */
  ObjectNode insert(String table, UUID userId, ObjectNode values);

  /** Changes the given fields of the owner's row; empty when the row is not the owner's. */
  Optional<ObjectNode> update(String table, UUID userId, UUID id, ObjectNode values);

  /** Removes the owner's row; false when there was none. */
  boolean delete(String table, UUID userId, UUID id);

  /** Removes the owner's rows matching {@code where}; returns how many went. */
  int deleteWhere(String table, UUID userId, Map<String, Object> where);

  /** Owners who have a finance profile, for the scheduled jobs (one user at a time). */
  List<UUID> ownersWithProfile();
}

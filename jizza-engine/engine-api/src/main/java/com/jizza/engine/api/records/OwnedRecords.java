package com.jizza.engine.api.records;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain storage for one product area's simple tables (Kai's {@code fin_} forms, Vita's {@code vita_}
 * forms). Rows travel as JSON objects with camelCase field names ({@code take_home_monthly} is
 * {@code takeHomeMonthly}); numbers are exact decimals.
 *
 * <p>Every call names the owner and touches only that owner's rows: another owner's row reads as
 * missing. The store checks table and column names against the database, never the meaning of a
 * value: which fields a form may set, and what they must hold, is decided by the use cases.
 */
public interface OwnedRecords {

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

  /**
   * Sets a secret (bytea) column of one of the user's rows, such as an encrypted number. The
   * caller encrypts; secret columns never appear in the rows the other methods return.
   */
  boolean writeSecret(String table, UUID userId, UUID id, String field, byte[] value);

  /** A secret column of one of the user's rows; empty when unset or not theirs. */
  Optional<byte[]> readSecret(String table, UUID userId, UUID id, String field);
}

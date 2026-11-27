package com.jizza.engine.api.finance;

import java.util.List;
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
public interface FinanceRecords extends com.jizza.engine.api.records.OwnedRecords {

  /** Owners who have a finance profile, for the scheduled jobs (one user at a time). */
  List<UUID> ownersWithProfile();
}

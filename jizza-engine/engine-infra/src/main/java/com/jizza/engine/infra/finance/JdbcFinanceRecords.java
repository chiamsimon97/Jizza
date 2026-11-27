package com.jizza.engine.infra.finance;

import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.infra.records.JdbcOwnedRecords;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link FinanceRecords}: the owner-scoped records store over Kai's personal finance tables. */
@Component
public class JdbcFinanceRecords extends JdbcOwnedRecords implements FinanceRecords {

  /** The personal finance tables this store serves. Phase 0's tables have their own store. */
  public static final List<String> TABLES_IN_ORDER = List.of(
      "fin_account", "fin_asset", "fin_net_worth_snapshot", "fin_category", "fin_income_source",
      "fin_expense_budget", "fin_import_batch", "fin_transaction", "fin_category_rule",
      "fin_liability", "fin_pledge", "fin_loan_payment", "fin_loan_given", "fin_insurance_policy",
      "fin_estate_record", "fin_health_check_run", "fin_action_item", "fin_life_event",
      "fin_review_run",
      // Phase 1b
      "fin_risk_assessment", "fin_tax_profile", "fin_plan", "fin_investment_budget",
      "fin_allocation_line", "fin_projection_run",
      // Phase 2
      "fin_holding_lot", "fin_earmark", "fin_earmark_event", "fin_realized_gain", "fin_node",
      "fin_rule", "fin_rule_template", "fin_list_item",
      // Connected sources
      "fin_sync_source", "fin_order", "fin_mail_source");
  static final Set<String> TABLES = Set.copyOf(TABLES_IN_ORDER);

  public JdbcFinanceRecords(JdbcTemplate jdbc) {
    super(jdbc, TABLES_IN_ORDER, "finance");
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> ownersWithProfile() {
    return jdbc().queryForList("select user_id from fin_profile order by user_id", UUID.class);
  }
}

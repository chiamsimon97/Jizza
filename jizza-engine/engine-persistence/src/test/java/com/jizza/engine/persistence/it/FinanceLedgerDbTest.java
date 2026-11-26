package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Kai Portfolio's Phase 2 tables (V55–V57): lots stay within their quantity, an earmark claims
 * either units of an instrument or an amount of an asset, the two logs are append-only, and
 * deleting the user still clears everything.
 */
@org.junit.jupiter.api.condition.EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available", disabledReason = "no PostgreSQL available")
class FinanceLedgerDbTest extends PostgresIntegrationTest {

  @PersistenceContext
  private EntityManager em;

  private UUID one(String sql, Object... args) {
    var q = em.createNativeQuery(sql);
    for (int i = 0; i < args.length; i++) q.setParameter(i + 1, args[i]);
    return (UUID) q.getSingleResult();
  }

  private void refused(Runnable statement, String message) {
    em.createNativeQuery("savepoint refused").executeUpdate();
    assertThatThrownBy(statement::run).hasMessageContaining(message);
    em.createNativeQuery("rollback to savepoint refused").executeUpdate();
  }

  record Setup(UUID user, UUID person, UUID account, UUID instrument, UUID plan) {}

  private Setup setup(String name) {
    UUID u = one("insert into users (email, password_hash) values (?, 'x') returning id",
        name + "-" + UUID.randomUUID() + "@jizza.local");
    UUID p = one("insert into fin_person (user_id, display_name, kind) values (?, 'Me', 'self') returning id", u);
    UUID a = one("insert into fin_account (user_id, person_id, kind) values (?, ?, 'demat') returning id", u, p);
    UUID i = one("insert into fin_instrument (name, type, asset_class, symbol, exchange) values (?, 'stock', 'equity', ?, 'NSE') returning id",
        "Test " + name, "T" + UUID.randomUUID().toString().substring(0, 8));
    UUID g = one("insert into fin_plan (user_id, type, name, target_date) values (?, 'retirement', 'Retire', date '2045-01-01') returning id", u);
    return new Setup(u, p, a, i, g);
  }

  @Test
  void lotsKeepRemainingWithinQuantity() {
    Setup s = setup("lots");
    UUID lot = one("""
        insert into fin_holding_lot (user_id, person_id, account_id, instrument_id, acquired_on, qty, remaining_qty, cost_per_unit, source)
        values (?, ?, ?, ?, date '2023-03-01', 40, 40, 1600, 'buy') returning id""", s.user(), s.person(), s.account(), s.instrument());
    assertThat(lot).isNotNull();
    refused(() -> one("""
        insert into fin_holding_lot (user_id, person_id, account_id, instrument_id, acquired_on, qty, remaining_qty, source)
        values (?, ?, ?, ?, date '2023-03-01', 10, 11, 'buy') returning id""", s.user(), s.person(), s.account(), s.instrument()),
        "fin_holding_lot_qty_check");
    refused(() -> one("""
        insert into fin_holding_lot (user_id, person_id, account_id, instrument_id, qty, remaining_qty, source)
        values (?, ?, ?, ?, 10, 10, 'buy') returning id""", s.user(), s.person(), s.account(), s.instrument()),
        "fin_holding_lot_date_check");
  }

  @Test
  void anEarmarkClaimsUnitsOrAnAmountNeverBoth() {
    Setup s = setup("earmarks");
    one("""
        insert into fin_earmark (user_id, plan_id, person_id, account_id, instrument_id, mode, units)
        values (?, ?, ?, ?, ?, 'units', 30) returning id""", s.user(), s.plan(), s.person(), s.account(), s.instrument());
    refused(() -> one("""
        insert into fin_earmark (user_id, plan_id, person_id, account_id, instrument_id, mode, units)
        values (?, ?, ?, ?, ?, 'units', 5) returning id""", s.user(), s.plan(), s.person(), s.account(), s.instrument()),
        "fin_earmark_units_one");
    refused(() -> one("""
        insert into fin_earmark (user_id, plan_id, person_id, account_id, instrument_id, mode, amount)
        values (?, ?, ?, ?, ?, 'amount', 5) returning id""", s.user(), s.plan(), s.person(), s.account(), s.instrument()),
        "fin_earmark_mode_check");
  }

  @Test
  void eventsAndRealisedGainsAreAppendOnlyButGoWithTheUser() {
    Setup s = setup("append");
    UUID event = one("""
        insert into fin_earmark_event (user_id, plan_id, kind, delta_units) values (?, ?, 'assign', 30) returning id""",
        s.user(), s.plan());
    UUID gain = one("""
        insert into fin_realized_gain (user_id, person_id, tax_year, instrument_id, sold_on, qty, proceeds, cost, gain, term, tax_class)
        values (?, ?, '2026-27', ?, date '2026-11-24', 30, 60000, 48000, 12000, 'lt', 'equity') returning id""",
        s.user(), s.person(), s.instrument());
    refused(() -> em.createNativeQuery("update fin_earmark_event set delta_units = 1 where id = ?1")
        .setParameter(1, event).executeUpdate(), "never changed or removed");
    refused(() -> em.createNativeQuery("delete from fin_realized_gain where id = ?1")
        .setParameter(1, gain).executeUpdate(), "never changed or removed");

    em.createNativeQuery("delete from users where id = ?1").setParameter(1, s.user()).executeUpdate();
    Number left = (Number) em.createNativeQuery("select count(*) from fin_realized_gain where user_id = ?1")
        .setParameter(1, s.user()).getSingleResult();
    assertThat(left.intValue()).isZero();
  }

  @Test
  void nodeKeysAreUniqueUnderOneParentAndRulesOncePerField() {
    Setup s = setup("tree");
    UUID equity = one("""
        insert into fin_node (user_id, plan_id, kind, node_key, name, target_pct) values (?, ?, 'asset_class', 'equity', 'Equity', 60) returning id""",
        s.user(), s.plan());
    refused(() -> one("""
        insert into fin_node (user_id, plan_id, kind, node_key, name, target_pct) values (?, ?, 'asset_class', 'equity', 'Equity', 40) returning id""",
        s.user(), s.plan()), "fin_node_key");
    refused(() -> one("""
        insert into fin_node (user_id, plan_id, parent_id, kind, node_key, name) values (?, ?, ?, 'sub_category', 'mid_cap', 'Mid cap') returning id""",
        s.user(), s.plan(), equity), "fin_node_pct_needed");
    one("""
        insert into fin_rule (user_id, scope_level, plan_id, node_id, rule_type, field, state, params)
        values (?, 'asset_class', ?, ?, 'stop_loss', 'stop_loss', 'set', '{"pct": 12}') returning id""", s.user(), s.plan(), equity);
    refused(() -> one("""
        insert into fin_rule (user_id, scope_level, plan_id, node_id, rule_type, field, state)
        values (?, 'asset_class', ?, ?, 'stop_loss', 'stop_loss', 'off') returning id""", s.user(), s.plan(), equity), "fin_rule_one");
    refused(() -> one("""
        insert into fin_rule (user_id, scope_level, plan_id, rule_type, field, state)
        values (?, 'user', ?, 'stop_loss', 'stop_loss', 'off') returning id""", s.user(), s.plan()), "fin_rule_scope_check");
  }
}

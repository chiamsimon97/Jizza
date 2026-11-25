package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Kai Portfolio's Phase 1a tables (V51–V53): kinds and enums are checked by the database, the one
 * live action per key rule, one will record per person, one monthly review per month, and
 * everything cascading from the user so hard delete clears it.
 */
@org.junit.jupiter.api.condition.EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available", disabledReason = "no PostgreSQL available")
class FinanceMoneyHealthDbTest extends PostgresIntegrationTest {

  @PersistenceContext
  private EntityManager em;

  private UUID user(String name) {
    return (UUID) em.createNativeQuery(
        "insert into users (email, password_hash) values (:e, 'x') returning id")
        .setParameter("e", name + "-" + UUID.randomUUID() + "@jizza.local").getSingleResult();
  }

  private UUID me(UUID u) {
    return (UUID) em.createNativeQuery(
        "insert into fin_person (user_id, display_name, kind) values (:u, 'Me', 'self') returning id")
        .setParameter("u", u).getSingleResult();
  }

  /** The statement fails on the named constraint; a savepoint keeps the test's transaction usable. */
  private void refused(Runnable statement, String constraint) {
    em.createNativeQuery("savepoint refused").executeUpdate();
    assertThatThrownBy(statement::run).hasMessageContaining(constraint);
    em.createNativeQuery("rollback to savepoint refused").executeUpdate();
  }

  private UUID insert(String sql, UUID u, UUID p) {
    return (UUID) em.createNativeQuery(sql).setParameter("u", u).setParameter("p", p).getSingleResult();
  }

  @Test
  void loanKindsAndModesAreCheckedByTheDatabase() {
    UUID u = user("loans");
    UUID p = me(u);
    UUID loan = insert("""
        insert into fin_liability (user_id, person_id, kind, outstanding, effective_annual_rate,
          repayment_mode, maturity_or_promised_date)
        values (:u, :p, 'GOLD_LOAN', 200000, 9.5, 'bullet', date '2027-05-31') returning id""", u, p);
    assertThat(loan).isNotNull();
    refused(() -> insert("""
        insert into fin_liability (user_id, person_id, kind) values (:u, :p, 'PAYDAY') returning id""", u, p), "fin_liability_kind_check");
    refused(() -> insert("""
        insert into fin_liability (user_id, person_id, kind, repayment_mode)
        values (:u, :p, 'HOME', 'balloon') returning id""", u, p), "fin_liability_mode_check");
    UUID pledge = (UUID) em.createNativeQuery("""
        insert into fin_pledge (user_id, liability_id, asset_kind, grams, purity_karat, valuation)
        values (:u, :l, 'gold', 30, 22, 280000) returning id""")
        .setParameter("u", u).setParameter("l", loan).getSingleResult();
    assertThat(pledge).isNotNull();
    refused(() -> em.createNativeQuery("""
        insert into fin_pledge (user_id, liability_id, asset_kind, purity_karat)
        values (:u, :l, 'gold', 30) returning id""")
        .setParameter("u", u).setParameter("l", loan).getSingleResult(), "fin_pledge_purity_check");
  }

  @Test
  void accountsAssetsAndPoliciesKeepToTheirKinds() {
    UUID u = user("kinds");
    UUID p = me(u);
    refused(() -> insert("""
        insert into fin_account (user_id, person_id, kind) values (:u, :p, 'crypto') returning id""", u, p), "fin_account_kind_check");
    refused(() -> insert("""
        insert into fin_account (user_id, person_id, kind, owner_share_pct)
        values (:u, :p, 'savings', 0) returning id""", u, p), "fin_account_share_check");
    refused(() -> insert("""
        insert into fin_asset (user_id, person_id, kind, name, asset_class)
        values (:u, :p, 'fd', 'FD', 'crypto') returning id""", u, p), "fin_asset_class_check");
    refused(() -> insert("""
        insert into fin_insurance_policy (user_id, person_id, kind) values (:u, :p, 'pet') returning id""", u, p), "fin_insurance_kind_check");
  }

  @Test
  void oneLiveActionPerKeyButHistoryStays() {
    UUID u = user("actions");
    String sql = """
        insert into fin_action_item (user_id, action_key, layer, title, status)
        values (:u, 'debt.high_interest', 'survival', 'Repay', :s) returning id""";
    em.createNativeQuery(sql).setParameter("u", u).setParameter("s", "done").getSingleResult();
    em.createNativeQuery(sql).setParameter("u", u).setParameter("s", "dismissed").getSingleResult();
    em.createNativeQuery(sql).setParameter("u", u).setParameter("s", "open").getSingleResult();
    refused(() -> em.createNativeQuery(sql).setParameter("u", u).setParameter("s", "later")
        .getSingleResult(), "fin_action_item_live");
  }

  @Test
  void oneWillRecordPerPersonAndOneMonthlyReviewPerMonth() {
    UUID u = user("estate");
    UUID p = me(u);
    insert("insert into fin_estate_record (user_id, person_id, will_status) values (:u, :p, 'draft') returning id", u, p);
    refused(() -> insert(
        "insert into fin_estate_record (user_id, person_id) values (:u, :p) returning id", u, p), "fin_estate_record_one");

    String review = "insert into fin_review_run (user_id, kind) values (:u, 'monthly') returning id";
    em.createNativeQuery(review).setParameter("u", u).getSingleResult();
    refused(() -> em.createNativeQuery(review).setParameter("u", u).getSingleResult(), "fin_review_run_month");
    em.createNativeQuery("insert into fin_review_run (user_id, kind) values (:u, 'life_event') returning id")
        .setParameter("u", u).getSingleResult();
  }

  @Test
  void everythingGoesWithTheUser() {
    UUID u = user("gone");
    UUID p = me(u);
    UUID loan = insert("insert into fin_liability (user_id, person_id, kind) values (:u, :p, 'PERSONAL') returning id", u, p);
    em.createNativeQuery("insert into fin_loan_payment (user_id, liability_id, due_or_paid_on, amount) values (:u, :l, date '2026-12-05', 8000)")
        .setParameter("u", u).setParameter("l", loan).executeUpdate();
    UUID cat = (UUID) em.createNativeQuery("insert into fin_category (user_id, name, bucket) values (:u, 'Food', 'need') returning id")
        .setParameter("u", u).getSingleResult();
    em.createNativeQuery("insert into fin_expense_budget (user_id, category_id, monthly_amount) values (:u, :c, 40000)")
        .setParameter("u", u).setParameter("c", cat).executeUpdate();
    em.createNativeQuery("insert into fin_health_check_run (user_id, score) values (:u, 37.5)")
        .setParameter("u", u).executeUpdate();
    em.createNativeQuery("insert into fin_net_worth_snapshot (user_id, as_of, assets, liabilities) values (:u, current_date, 1, 2)")
        .setParameter("u", u).executeUpdate();

    em.createNativeQuery("delete from users where id = :u").setParameter("u", u).executeUpdate();
    for (String t : new String[] {"fin_person", "fin_liability", "fin_loan_payment", "fin_category",
        "fin_expense_budget", "fin_health_check_run", "fin_net_worth_snapshot"}) {
      Number left = (Number) em.createNativeQuery("select count(*) from " + t + " where user_id = :u")
          .setParameter("u", u).getSingleResult();
      assertThat(left.intValue()).as(t).isZero();
    }
  }

  @Test
  void netWorthSnapshotsAreOnePerPersonAndDay() {
    UUID u = user("snap");
    String sql = "insert into fin_net_worth_snapshot (user_id, as_of, assets, liabilities) values (:u, date '2026-12-01', 1, 0)";
    em.createNativeQuery(sql).setParameter("u", u).executeUpdate();
    refused(() -> em.createNativeQuery(sql).setParameter("u", u).executeUpdate(), "fin_net_worth_snapshot_one");
  }

  @Test
  void phase1aDefaultsAreSeeded() {
    Number n = (Number) em.createNativeQuery("""
        select count(*) from fin_config where user_id is null
          and key in ('debt.prepay_or_invest', 'cashflow.default_categories')""").getSingleResult();
    assertThat(n.intValue()).isEqualTo(2);
  }
}

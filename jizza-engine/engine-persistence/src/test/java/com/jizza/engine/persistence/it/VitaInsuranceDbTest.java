package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Health insurance and claims (V74): the government_scheme kind on Kai's policy table, one detail
 * row per policy, claims checked, and both going with their policy.
 */
@org.junit.jupiter.api.condition.EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available", disabledReason = "no PostgreSQL available")
class VitaInsuranceDbTest extends PostgresIntegrationTest {

  @PersistenceContext
  private EntityManager em;

  private UUID user() {
    return (UUID) em.createNativeQuery("insert into users (email, password_hash) values (:e, 'x') returning id")
        .setParameter("e", "vins-" + UUID.randomUUID() + "@jizza.local").getSingleResult();
  }

  private UUID person(UUID user) {
    return (UUID) em.createNativeQuery("insert into fin_person (user_id, display_name, kind) values (:u, 'Father', 'parent') returning id")
        .setParameter("u", user).getSingleResult();
  }

  private UUID policy(UUID user, UUID person, String kind) {
    return (UUID) em.createNativeQuery("""
        insert into fin_insurance_policy (user_id, person_id, kind, sum_assured, renewal_date)
        values (:u, :p, :k, 1000000, date '2027-03-31') returning id""")
        .setParameter("u", user).setParameter("p", person).setParameter("k", kind).getSingleResult();
  }

  private void claim(UUID user, UUID person, UUID policy, String status, String admitted, String discharged, int paid) {
    em.createNativeQuery("""
        insert into vita_claim (user_id, person_id, policy_id, admitted_on, discharged_on, amount_claimed, amount_paid, status)
        values (:u, :p, :pol, cast(:a as date), cast(:d as date), 120000, :paid, :s)""")
        .setParameter("u", user).setParameter("p", person).setParameter("pol", policy).setParameter("a", admitted)
        .setParameter("d", discharged).setParameter("paid", paid).setParameter("s", status).executeUpdate();
  }

  private int count(String sql, Object... params) {
    var q = em.createNativeQuery(sql);
    for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
    return ((Number) q.getSingleResult()).intValue();
  }

  /** Runs a statement that must fail, inside a savepoint so the test's transaction goes on. */
  private String refused(Runnable statement) {
    em.createNativeQuery("savepoint refused").executeUpdate();
    try {
      statement.run();
    } catch (RuntimeException e) {
      em.createNativeQuery("rollback to savepoint refused").executeUpdate();
      Throwable t = e;
      StringBuilder all = new StringBuilder();
      while (t != null) {
        all.append(t.getMessage()).append(' ');
        t = t.getCause();
      }
      return all.toString();
    }
    throw new AssertionError("the statement was accepted");
  }

  @Test
  void aGovernmentSchemeIsAKindAndEveryEarlierKindStays() {
    UUID u = user();
    UUID p = person(u);
    for (String kind : new String[] {"government_scheme", "health", "term", "ulip", "super_topup"}) {
      assertThat(policy(u, p, kind)).isNotNull();
    }
    assertThat(refused(() -> policy(u, p, "travel"))).contains("fin_insurance_kind_check");
  }

  @Test
  void oneDetailPerPolicyAndClaimsAreChecked() {
    UUID u = user();
    UUID p = person(u);
    UUID pol = policy(u, p, "health");
    em.createNativeQuery("insert into vita_policy_detail (user_id, policy_id, policy_number_last4, copay_pct) values (:u, :p, '3456', 10)")
        .setParameter("u", u).setParameter("p", pol).executeUpdate();
    assertThat(refused(() -> em.createNativeQuery("insert into vita_policy_detail (user_id, policy_id) values (:u, :p)")
        .setParameter("u", u).setParameter("p", pol).executeUpdate())).contains("vita_policy_detail_one");
    UUID other = policy(u, p, "super_topup");
    assertThat(refused(() -> em.createNativeQuery("insert into vita_policy_detail (user_id, policy_id, copay_pct) values (:u, :p, 120)")
        .setParameter("u", u).setParameter("p", other).executeUpdate())).contains("vita_policy_detail_copay_check");

    claim(u, p, pol, "settled", "2026-08-02", "2026-08-04", 105000);
    assertThat(refused(() -> claim(u, p, pol, "paid", "2026-08-02", null, 0))).contains("vita_claim_status_check");
    assertThat(refused(() -> claim(u, p, pol, "filed", "2026-08-04", "2026-08-02", 0))).contains("vita_claim_dates_check");
    assertThat(refused(() -> claim(u, p, pol, "filed", "2026-08-02", null, -1))).contains("vita_claim_amounts_check");
  }

  @Test
  void detailAndClaimsGoWithTheirPolicyAndClaimsWithTheirPerson() {
    UUID u = user();
    UUID holder = person(u);
    UUID father = person(u);
    UUID pol = policy(u, holder, "health");
    em.createNativeQuery("insert into vita_policy_detail (user_id, policy_id) values (:u, :p)")
        .setParameter("u", u).setParameter("p", pol).executeUpdate();
    claim(u, father, pol, "settled", "2026-08-02", null, 105000);
    claim(u, holder, pol, "filed", "2026-09-02", null, 0);
    em.createNativeQuery("delete from fin_person where id = :p").setParameter("p", father).executeUpdate();
    assertThat(count("select count(*) from vita_claim where policy_id = ?1", pol)).isEqualTo(1);
    em.createNativeQuery("delete from fin_insurance_policy where id = :p").setParameter("p", pol).executeUpdate();
    assertThat(count("select count(*) from vita_claim where policy_id = ?1", pol)).isZero();
    assertThat(count("select count(*) from vita_policy_detail where policy_id = ?1", pol)).isZero();
  }
}

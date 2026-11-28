package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Vita's lab tables (V70): the reference list holds no range and only sourced, unverified
 * conversions; reports, results and checks keep their checks and go with the person and the account.
 */
@org.junit.jupiter.api.condition.EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available", disabledReason = "no PostgreSQL available")
class VitaLabDbTest extends PostgresIntegrationTest {

  @PersistenceContext
  private EntityManager em;

  private UUID user() {
    return (UUID) em.createNativeQuery("insert into users (email, password_hash) values (:e, 'x') returning id")
        .setParameter("e", "lab-" + UUID.randomUUID() + "@jizza.local").getSingleResult();
  }

  private UUID person(UUID user) {
    return (UUID) em.createNativeQuery("insert into fin_person (user_id, display_name, kind) values (:u, 'Someone', 'parent') returning id")
        .setParameter("u", user).getSingleResult();
  }

  private UUID report(UUID u, UUID p) {
    return (UUID) em.createNativeQuery("insert into vita_lab_report (user_id, person_id, collected_on) values (:u, :p, date '2026-09-15') returning id")
        .setParameter("u", u).setParameter("p", p).getSingleResult();
  }

  private UUID result(UUID u, UUID p, UUID r, String flag) {
    return (UUID) em.createNativeQuery("""
        insert into vita_lab_result (user_id, person_id, report_id, test_code, test_name_as_printed, value_num, flag)
        values (:u, :p, :r, 'hba1c', 'HbA1c', 7.1, :f) returning id""")
        .setParameter("u", u).setParameter("p", p).setParameter("r", r).setParameter("f", flag).getSingleResult();
  }

  private int count(String sql, Object... params) {
    var q = em.createNativeQuery(sql);
    for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
    return ((Number) q.getSingleResult()).intValue();
  }

  @Test
  void theReferenceListIsSeededWithNoRangeAndOnlySourcedUnverifiedConversions() {
    assertThat(count("select count(*) from vita_lab_test")).isGreaterThanOrEqualTo(40);
    assertThat(count("select count(*) from vita_lab_test where 'FBS' = any(synonyms) and code = 'fasting_glucose'")).isEqualTo(1);
    // Every conversion names a source and none is verified yet.
    assertThat(count("""
        select count(*) from vita_lab_test t, jsonb_array_elements(t.conversions) c
         where coalesce(c->>'source', '') = '' or c->>'verifiedOn' is not null""")).isZero();
    assertThat(count("select count(*) from vita_lab_test where jsonb_array_length(conversions) > 0")).isEqualTo(5);
    // No conversion for glucose or creatinine: spec 5.4 names no source for them.
    assertThat(count("select count(*) from vita_lab_test where code in ('fasting_glucose', 'creatinine') and conversions <> '[]'::jsonb")).isZero();
    assertThat(count("""
        select count(*) from vita_lab_test where code = 'ldl' and (conversions->0->>'multiply')::numeric = 38.67""")).isEqualTo(1);
    assertThat(count("""
        select count(*) from vita_config where key = 'clinical.formulas.eag' and source_url is not null and verified_on is null
           and (value->>'multiply')::numeric = 28.7 and (value->>'add')::numeric = -46.7""")).isEqualTo(1);
  }

  @Test
  void resultsGoWithTheirReportAndReportsWithThePerson() {
    UUID u = user();
    UUID p = person(u);
    UUID r = report(u, p);
    UUID x = result(u, p, r, "high");
    em.createNativeQuery("delete from vita_lab_report where id = :r").setParameter("r", r).executeUpdate();
    assertThat(count("select count(*) from vita_lab_result where id = ?1", x)).isZero();
    UUID r2 = report(u, p);
    result(u, p, r2, "normal");
    em.createNativeQuery("delete from users where id = :u").setParameter("u", u).executeUpdate();
    assertThat(count("select count(*) from vita_lab_report where id = ?1", r2)).isZero();
  }

  @Test
  void aFlagIsOneOfTheList() {
    UUID u = user();
    UUID p = person(u);
    UUID r = report(u, p);
    assertThatThrownBy(() -> result(u, p, r, "abnormal")).hasMessageContaining("vita_lab_result_flag_check");
  }

  @Test
  void aCheckHasADate() {
    UUID u = user();
    UUID p = person(u);
    assertThatThrownBy(() -> em.createNativeQuery("insert into vita_check (user_id, person_id, kind) values (:u, :p, 'eye')")
        .setParameter("u", u).setParameter("p", p).executeUpdate()).hasMessageContaining("vita_check_has_date");
  }

  @Test
  void aCheckHasAKnownKind() {
    UUID u = user();
    UUID p = person(u);
    assertThatThrownBy(() -> em.createNativeQuery("insert into vita_check (user_id, person_id, kind, done_on) values (:u, :p, 'xray', now())")
        .setParameter("u", u).setParameter("p", p).executeUpdate()).hasMessageContaining("vita_check_kind_check");
  }
}

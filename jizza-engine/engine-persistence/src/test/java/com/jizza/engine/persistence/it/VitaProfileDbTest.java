package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Vita's Phase 0 tables (V66): one profile per person, the checks on its values, readings that a
 * device can send twice without doubling, cascades from the user and the person, and the seeded
 * figures: every clinical one unverified and with its source, none of the "not set" ones present.
 */
@org.junit.jupiter.api.condition.EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available", disabledReason = "no PostgreSQL available")
class VitaProfileDbTest extends PostgresIntegrationTest {

  @PersistenceContext
  private EntityManager em;

  private UUID user(String name) {
    return (UUID) em.createNativeQuery(
        "insert into users (email, password_hash) values (:e, 'x') returning id")
        .setParameter("e", name + "-" + UUID.randomUUID() + "@jizza.local").getSingleResult();
  }

  private UUID person(UUID user, String kind) {
    return (UUID) em.createNativeQuery("""
        insert into fin_person (user_id, display_name, kind) values (:u, 'Someone', :k) returning id""")
        .setParameter("u", user).setParameter("k", kind).getSingleResult();
  }

  private int count(String sql, Object... params) {
    var q = em.createNativeQuery(sql);
    for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
    return ((Number) q.getSingleResult()).intValue();
  }

  @Test
  void onePersonHasOneProfile() {
    UUID u = user("one");
    UUID amma = person(u, "parent");
    em.createNativeQuery("insert into vita_profile (user_id, person_id) values (:u, :p)")
        .setParameter("u", u).setParameter("p", amma).executeUpdate();
    assertThat(count("select count(*) from vita_profile where person_id = ?1 and timezone = 'Asia/Kolkata' "
        + "and reminder_mode = 'each_slot' and quick_start = '{}'::jsonb", amma)).isEqualTo(1);
    // Last: a failed statement ends the test's transaction.
    assertThatThrownBy(() -> em.createNativeQuery("insert into vita_profile (user_id, person_id) values (:u, :p)")
        .setParameter("u", u).setParameter("p", amma).executeUpdate())
        .hasMessageContaining("vita_profile_one_per_person");
  }

  @Test
  void profileValuesAreChecked() {
    UUID u = user("checks");
    UUID me = person(u, "self");
    assertThatThrownBy(() -> em.createNativeQuery(
        "insert into vita_profile (user_id, person_id, blood_group) values (:u, :p, 'O+')")
        .setParameter("u", u).setParameter("p", me).executeUpdate())
        .hasMessageContaining("vita_profile_blood_group_check");
  }

  @Test
  void aDeviceReadingSentTwiceIsStoredOnce() {
    UUID u = user("device");
    UUID me = person(u, "self");
    String insert = """
        insert into vita_measurement (user_id, person_id, kind, value, unit, source, external_id, taken_at)
        values (:u, :p, 'weight', 70.2, 'kg', 'health_connect', 'hc-1', now())""";
    em.createNativeQuery(insert).setParameter("u", u).setParameter("p", me).executeUpdate();
    // Typed readings have no external id and may repeat.
    for (int i = 0; i < 2; i++) {
      em.createNativeQuery("insert into vita_measurement (user_id, person_id, kind, value) values (:u, :p, 'weight', 70)")
          .setParameter("u", u).setParameter("p", me).executeUpdate();
    }
    assertThat(count("select count(*) from vita_measurement where person_id = ?1", me)).isEqualTo(3);
    assertThatThrownBy(() -> em.createNativeQuery(insert).setParameter("u", u).setParameter("p", me).executeUpdate())
        .hasMessageContaining("vita_measurement_external_once");
  }

  @Test
  void deletingThePersonOrTheUserClearsTheRecord() {
    UUID u = user("gone");
    UUID amma = person(u, "parent");
    UUID me = person(u, "self");
    for (UUID p : List.of(amma, me)) {
      em.createNativeQuery("insert into vita_profile (user_id, person_id) values (:u, :p)")
          .setParameter("u", u).setParameter("p", p).executeUpdate();
      em.createNativeQuery("insert into vita_allergy (user_id, person_id, substance) values (:u, :p, 'Dust')")
          .setParameter("u", u).setParameter("p", p).executeUpdate();
      em.createNativeQuery("insert into vita_emergency_contact (user_id, person_id, name, phone) values (:u, :p, 'R', '1234')")
          .setParameter("u", u).setParameter("p", p).executeUpdate();
    }
    em.createNativeQuery("insert into vita_config (user_id, key, scope, value) values (:u, 'display.units', 'user', '{}'::jsonb)")
        .setParameter("u", u).executeUpdate();

    em.createNativeQuery("delete from fin_person where id = :p").setParameter("p", amma).executeUpdate();
    assertThat(count("select count(*) from vita_allergy where person_id = ?1", amma)).isZero();
    assertThat(count("select count(*) from vita_allergy where user_id = ?1", u)).isEqualTo(1);

    em.createNativeQuery("delete from users where id = :u").setParameter("u", u).executeUpdate();
    assertThat(count("""
        select (select count(*) from vita_profile where user_id = ?1)
             + (select count(*) from vita_allergy where user_id = ?1)
             + (select count(*) from vita_emergency_contact where user_id = ?1)
             + (select count(*) from vita_config where user_id = ?1)""", u)).isZero();
  }

  @Test
  void clinicalFiguresAreSeededOnlyWithASourceAndAllUnverified() {
    assertThat(count("select count(*) from vita_config where user_id is null and key like 'clinical.%' and verified_on is not null"))
        .as("verified on seeding").isZero();
    // Every seeded row that carries a number has its source recorded; only the chat-words list
    // (no number) and the "as General" references (no number) have none.
    @SuppressWarnings("unchecked")
    List<String> withoutSource = em.createNativeQuery("""
        select key from vita_config where user_id is null and key like 'clinical.%' and source_url is null
         order by key""").getResultList();
    assertThat(withoutSource).containsExactly("clinical.ranges.hypertension.bp_clinic",
        "clinical.ranges.hypertension.bp_home", "clinical.redflags.chat_words");
    assertThat(count("""
        select count(*) from vita_config where user_id is null and key in (
          'clinical.redflags.high_sugar', 'clinical.redflags.spo2', 'clinical.redflags.temperature',
          'clinical.ranges.general.glucose_fasting', 'clinical.ranges.general.glucose_after_meal',
          'clinical.ranges.general.spo2', 'clinical.ranges.general.resting_heart_rate',
          'clinical.ranges.general.temperature', 'clinical.intervals.diabetes_t2.kidney_function',
          'clinical.intervals.diabetes_t2.lipids', 'clinical.intervals.hypertension.bp_checks')"""))
        .as("figures the spec marks not set").isZero();
    assertThat(count("""
        select count(*) from vita_config where user_id is null and key = 'clinical.redflags.bp'
           and (value->>'systolicAbove')::int = 180 and (value->>'diastolicAbove')::int = 120"""))
        .isEqualTo(1);
    assertThat(count("select count(*) from vita_config where user_id is null and key = 'emergency.numbers' "
        + "and value->>'emergency' = '112'")).isEqualTo(1);
  }
}

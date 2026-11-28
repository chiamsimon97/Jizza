package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Vita's documents table (V69): its checks, what goes with the person and the account, what a
 * removed file or doctor leaves behind, and the settings it seeds.
 */
@org.junit.jupiter.api.condition.EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available", disabledReason = "no PostgreSQL available")
class VitaDocumentDbTest extends PostgresIntegrationTest {

  @PersistenceContext
  private EntityManager em;

  private UUID user(String name) {
    return (UUID) em.createNativeQuery("insert into users (email, password_hash) values (:e, 'x') returning id")
        .setParameter("e", name + "-" + UUID.randomUUID() + "@jizza.local").getSingleResult();
  }

  private UUID person(UUID user) {
    return (UUID) em.createNativeQuery("insert into fin_person (user_id, display_name, kind) values (:u, 'Someone', 'parent') returning id")
        .setParameter("u", user).getSingleResult();
  }

  private UUID file(UUID user) {
    return (UUID) em.createNativeQuery("""
        insert into files (user_id, filename, content_type, size_bytes, storage_ref, scan_status)
        values (:u, 'report.pdf', 'application/pdf', 10, :r, 'not_scanned') returning id""")
        .setParameter("u", user).setParameter("r", "ref-" + UUID.randomUUID()).getSingleResult();
  }

  private UUID document(UUID user, UUID person, UUID file) {
    return (UUID) em.createNativeQuery("""
        insert into vita_document (user_id, person_id, kind, file_id, content_type, extraction_status, draft)
        values (:u, :p, 'lab_report', :f, 'application/pdf', 'pending', '{"tests": []}'::jsonb) returning id""")
        .setParameter("u", user).setParameter("p", person).setParameter("f", file).getSingleResult();
  }

  private int count(String sql, Object... params) {
    var q = em.createNativeQuery(sql);
    for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
    return ((Number) q.getSingleResult()).intValue();
  }

  @Test
  void aDocumentKeepsItsRowWhenTheFileGoesAndGoesWithThePerson() {
    UUID u = user("doc");
    UUID p = person(u);
    UUID f = file(u);
    UUID d = document(u, p, f);
    em.createNativeQuery("delete from files where id = :f").setParameter("f", f).executeUpdate();
    assertThat(count("select count(*) from vita_document where id = ?1 and file_id is null", d)).isEqualTo(1);
    em.createNativeQuery("delete from fin_person where id = :p").setParameter("p", p).executeUpdate();
    assertThat(count("select count(*) from vita_document where id = ?1", d)).isZero();
  }

  @Test
  void deletingTheAccountClearsItsDocuments() {
    UUID u = user("gone");
    UUID d = document(u, person(u), file(u));
    em.createNativeQuery("delete from users where id = :u").setParameter("u", u).executeUpdate();
    assertThat(count("select count(*) from vita_document where id = ?1", d)).isZero();
  }

  @Test
  void theKindIsOneOfTheList() {
    UUID u = user("kind");
    UUID p = person(u);
    assertThatThrownBy(() -> em.createNativeQuery("insert into vita_document (user_id, person_id, kind) values (:u, :p, 'xray')")
        .setParameter("u", u).setParameter("p", p).executeUpdate()).hasMessageContaining("vita_document_kind_check");
  }

  @Test
  void theReadingStatusIsOneOfTheList() {
    UUID u = user("status");
    UUID p = person(u);
    assertThatThrownBy(() -> em.createNativeQuery(
        "insert into vita_document (user_id, person_id, extraction_status) values (:u, :p, 'reading')")
        .setParameter("u", u).setParameter("p", p).executeUpdate()).hasMessageContaining("vita_document_extraction_check");
  }

  @Test
  void theSettingsAreSeededAndTheShorthandIsUnverified() {
    assertThat(count("""
        select count(*) from vita_config where key = 'extraction.limits' and user_id is null
           and (value->>'pagesPerDocument')::int > 0 and (value->>'documentsPerDay')::int > 0""")).isEqualTo(1);
    assertThat(count("select count(*) from vita_config where key = 'reminder.slot_times' and value->>'morning' = '08:00'"))
        .isEqualTo(1);
    assertThat(count("""
        select count(*) from vita_config where key = 'clinical.rx_shorthand' and verified_on is null
           and value->'codes'->'BD'->'slots' = '["morning", "night"]'::jsonb""")).isEqualTo(1);
  }
}

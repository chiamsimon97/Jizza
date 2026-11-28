package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The Family plan and Vita sharing (V73): a workspace of type family; one active share per person,
 * member and section, and one member linked as the person themself; a share that ends keeps its
 * row with a reason; consent notes are for money or for health; care notes go with their person.
 */
@org.junit.jupiter.api.condition.EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available", disabledReason = "no PostgreSQL available")
class VitaShareDbTest extends PostgresIntegrationTest {

  @PersistenceContext
  private EntityManager em;

  private UUID user() {
    return (UUID) em.createNativeQuery("insert into users (email, password_hash) values (:e, 'x') returning id")
        .setParameter("e", "vshare-" + UUID.randomUUID() + "@jizza.local").getSingleResult();
  }

  private UUID person(UUID user) {
    return (UUID) em.createNativeQuery("insert into fin_person (user_id, display_name, kind) values (:u, 'Mother', 'parent') returning id")
        .setParameter("u", user).getSingleResult();
  }

  private UUID family(UUID owner) {
    return (UUID) em.createNativeQuery("insert into workspaces (name, type, created_by_user_id) values ('Our Family', 'family', :u) returning id")
        .setParameter("u", owner).getSingleResult();
  }

  private UUID share(UUID owner, UUID ws, UUID person, UUID grantee, boolean asSelf, String section, String access) {
    return (UUID) em.createNativeQuery("""
        insert into vita_share (user_id, workspace_id, person_id, grantee_user_id, as_self, section, access)
        values (:u, :w, :p, :g, :s, :sec, :a) returning id""")
        .setParameter("u", owner).setParameter("w", ws).setParameter("p", person).setParameter("g", grantee)
        .setParameter("s", asSelf).setParameter("sec", section).setParameter("a", access).getSingleResult();
  }

  private void end(UUID share, String reason) {
    em.createNativeQuery("update vita_share set ended_at = now(), ended_reason = :r where id = :id")
        .setParameter("r", reason).setParameter("id", share).executeUpdate();
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
  void aFamilyIsAWorkspaceTypeAndAnythingElseIsRefused() {
    UUID u = user();
    assertThat(family(u)).isNotNull();
    assertThat(refused(() -> em.createNativeQuery(
        "insert into workspaces (name, type, created_by_user_id) values ('X', 'household', :u)")
        .setParameter("u", u).executeUpdate())).contains("workspaces_type_check");
  }

  @Test
  void oneActiveSharePerSectionAndOnePersonThemselfAndEndedRowsStay() {
    UUID owner = user();
    UUID brother = user();
    UUID mother = user();
    UUID ws = family(owner);
    UUID p = person(owner);
    UUID first = share(owner, ws, p, brother, false, "medicines", "edit");
    assertThat(refused(() -> share(owner, ws, p, brother, false, "medicines", "view")))
        .contains("vita_share_one_active");
    end(first, "replaced");
    share(owner, ws, p, brother, false, "medicines", "view");
    assertThat(count("select count(*) from vita_share where person_id = ?1", p)).isEqualTo(2);

    share(owner, ws, p, mother, true, "medicines", "edit");
    assertThat(refused(() -> share(owner, ws, p, brother, true, "medicines", "view"))).contains("vita_share_one");
    assertThat(refused(() -> share(owner, ws, p, owner, false, "labs", "view")))
        .contains("vita_share_not_self");
    assertThat(refused(() -> share(owner, ws, p, brother, false, "cycle", "view")))
        .contains("vita_share_section_check");
    assertThat(refused(() -> share(owner, ws, p, brother, false, "labs", "own")))
        .contains("vita_share_access_check");
    UUID labs = share(owner, ws, p, brother, false, "labs", "view");
    assertThat(refused(() -> end(labs, "forgotten"))).contains("vita_share_reason_check");
    assertThat(refused(() -> em.createNativeQuery("update vita_share set ended_at = now() where id = :id")
        .setParameter("id", labs).executeUpdate())).contains("vita_share_ended_check");
  }

  @Test
  void consentNotesAreForMoneyOrHealthAndEarlierOnesAreForMoney() {
    UUID u = user();
    UUID p = person(u);
    UUID money = (UUID) em.createNativeQuery("insert into fin_consent (user_id, person_id, scope) values (:u, :p, 'view') returning id")
        .setParameter("u", u).setParameter("p", p).getSingleResult();
    assertThat(count("select count(*) from fin_consent where id = ?1 and domain = 'finance'", money)).isEqualTo(1);
    em.createNativeQuery("insert into fin_consent (user_id, person_id, scope, domain) values (:u, :p, 'view', 'health')")
        .setParameter("u", u).setParameter("p", p).executeUpdate();
    assertThat(refused(() -> em.createNativeQuery(
        "insert into fin_consent (user_id, person_id, scope, domain) values (:u, :p, 'view', 'travel')")
        .setParameter("u", u).setParameter("p", p).executeUpdate())).contains("fin_consent_domain_check");
  }

  @Test
  void sharesAndCareNotesGoWithTheirPersonAndAFamilyRemovedTakesItsShares() {
    UUID owner = user();
    UUID brother = user();
    UUID ws = family(owner);
    UUID p = person(owner);
    share(owner, ws, p, brother, false, "visits", "view");
    em.createNativeQuery("insert into vita_care_note (user_id, person_id, text, written_by_user_id) values (:u, :p, 'Bought strips', :b)")
        .setParameter("u", owner).setParameter("p", p).setParameter("b", brother).executeUpdate();
    // The writer's account going keeps the note, unsigned.
    em.createNativeQuery("delete from users where id = :b").setParameter("b", brother).executeUpdate();
    assertThat(count("select count(*) from vita_care_note where person_id = ?1 and written_by_user_id is null", p)).isEqualTo(1);
    assertThat(count("select count(*) from vita_share where person_id = ?1", p)).isZero();

    UUID helper = user();
    share(owner, ws, p, helper, false, "visits", "view");
    em.createNativeQuery("delete from workspaces where id = :w").setParameter("w", ws).executeUpdate();
    assertThat(count("select count(*) from vita_share where person_id = ?1", p)).isZero();
    em.createNativeQuery("delete from fin_person where id = :p").setParameter("p", p).executeUpdate();
    assertThat(count("select count(*) from vita_care_note where person_id = ?1", p)).isZero();
  }

  @Test
  void theMyMedicinesSettingIsSeeded() {
    assertThat(count("select count(*) from vita_config where key = 'ui.open_my_medicines' and user_id is null")).isEqualTo(1);
  }
}

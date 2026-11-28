package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Vita's mail sources (V72): a known status only, and each goes with its server, person and account. */
@org.junit.jupiter.api.condition.EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available", disabledReason = "no PostgreSQL available")
class VitaMailSourceDbTest extends PostgresIntegrationTest {

  @PersistenceContext
  private EntityManager em;

  private UUID user() {
    return (UUID) em.createNativeQuery("insert into users (email, password_hash) values (:e, 'x') returning id")
        .setParameter("e", "vmail-" + UUID.randomUUID() + "@jizza.local").getSingleResult();
  }

  private UUID person(UUID user) {
    return (UUID) em.createNativeQuery("insert into fin_person (user_id, display_name, kind) values (:u, 'Someone', 'parent') returning id")
        .setParameter("u", user).getSingleResult();
  }

  private UUID server(UUID user) {
    UUID ws = (UUID) em.createNativeQuery("insert into workspaces (name, type, created_by_user_id) values ('Mine', 'organization', :u) returning id")
        .setParameter("u", user).getSingleResult();
    return (UUID) em.createNativeQuery("""
        insert into mcp_servers (workspace_id, user_id, name, slug, url) values (:w, :u, 'Mail', :slug, 'http://127.0.0.1:1/mcp') returning id""")
        .setParameter("w", ws).setParameter("u", user).setParameter("slug", "mail-" + UUID.randomUUID()).getSingleResult();
  }

  private UUID source(UUID u, UUID s, UUID p, String status) {
    return (UUID) em.createNativeQuery("""
        insert into vita_mail_source (user_id, server_id, person_id, sender, last_status)
        values (:u, :s, :p, 'reports@lab.test', :st) returning id""")
        .setParameter("u", u).setParameter("s", s).setParameter("p", p).setParameter("st", status).getSingleResult();
  }

  private int count(String sql, Object... params) {
    var q = em.createNativeQuery(sql);
    for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
    return ((Number) q.getSingleResult()).intValue();
  }

  @Test
  void aSourceGoesWithItsServerItsPersonAndTheAccount() {
    UUID u = user();
    UUID s = server(u);
    UUID p = person(u);
    UUID a = source(u, s, p, "to_review");
    assertThat(count("select count(*) from vita_mail_source where id = ?1 and handled = '[]'::jsonb and enabled", a)).isEqualTo(1);
    em.createNativeQuery("delete from mcp_servers where id = :s").setParameter("s", s).executeUpdate();
    assertThat(count("select count(*) from vita_mail_source where id = ?1", a)).isZero();
    UUID s2 = server(u);
    UUID b = source(u, s2, p, null);
    em.createNativeQuery("delete from fin_person where id = :p").setParameter("p", p).executeUpdate();
    assertThat(count("select count(*) from vita_mail_source where id = ?1", b)).isZero();
    UUID c = source(u, s2, person(u), "nothing_new");
    em.createNativeQuery("delete from users where id = :u").setParameter("u", u).executeUpdate();
    assertThat(count("select count(*) from vita_mail_source where id = ?1", c)).isZero();
  }

  @Test
  void aStatusIsOneOfKaisFive() {
    UUID u = user();
    UUID s = server(u);
    UUID p = person(u);
    assertThatThrownBy(() -> source(u, s, p, "done")).hasMessageContaining("vita_mail_source_status_check");
  }
}

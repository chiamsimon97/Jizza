package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.persistence.entity.finance.FinPersonEntity;
import com.jizza.engine.persistence.repository.finance.FinConfigRepository;
import com.jizza.engine.persistence.repository.finance.FinPersonRepository;
import com.jizza.engine.persistence.repository.finance.FinProfileRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Kai Portfolio's Phase 0 tables (V50): ownership, the one "Me" rule, and the seeded defaults. */
@org.junit.jupiter.api.condition.EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available", disabledReason = "no PostgreSQL available")
class FinanceFoundationDbTest extends PostgresIntegrationTest {

  @PersistenceContext
  private EntityManager em;

  @Autowired private FinProfileRepository profiles;
  @Autowired private FinPersonRepository people;
  @Autowired private FinConfigRepository config;

  private UUID user(String name) {
    return (UUID) em.createNativeQuery(
        "insert into users (email, password_hash) values (:e, 'x') returning id")
        .setParameter("e", name + "-" + UUID.randomUUID() + "@jizza.local").getSingleResult();
  }

  @Test
  void kaiDefaultsAndRegulatoryFiguresAreSeededUnverified() {
    var system = config.findVisibleTo(UUID.randomUUID());
    assertThat(system).isNotEmpty().allMatch(c -> c.getUserId() == null);
    assertThat(system).extracting(c -> c.getKey())
        .contains("health.checks", "allocation.long_by_age", "returns.expected",
            "risk.questionnaire_v1", "tax.new_regime", "tax.capital_gains", "regulatory.gold_loan_ltv");
    assertThat(system).filteredOn(c -> c.getKey().startsWith("tax.") || c.getKey().startsWith("regulatory."))
        .allMatch(c -> c.getVerifiedOn() == null);
    var weights = system.stream().filter(c -> c.getKey().equals("health.checks")).findFirst().orElseThrow()
        .getValue();
    int total = 0;
    for (var check : weights) total += check.path("weight").asInt();
    assertThat(total).as("Money Health weights add up to 100").isEqualTo(100);
  }

  @Test
  void eachAccountHasOneMeAndOneProfile() {
    UUID u = user("me");
    em.createNativeQuery("insert into fin_profile (user_id) values (:u)").setParameter("u", u).executeUpdate();
    em.createNativeQuery("insert into fin_person (user_id, display_name, kind) values (:u, 'Me', 'self')")
        .setParameter("u", u).executeUpdate();

    assertThatThrownBy(() -> em.createNativeQuery(
        "insert into fin_person (user_id, display_name, kind) values (:u, 'Me 2', 'self')")
        .setParameter("u", u).executeUpdate()).hasMessageContaining("fin_person_one_self");
  }

  @Test
  void managedPeopleBelongToTheirOwnerOnly() {
    UUID asha = user("asha");
    UUID dev = user("dev");
    FinPersonEntity child = new FinPersonEntity();
    child.setUserId(asha);
    child.setDisplayName("Riya");
    child.setKind("child");
    child.setType("minor");
    child = people.saveAndFlush(child);

    assertThat(people.findByIdAndUserId(child.getId(), asha)).isPresent();
    assertThat(people.findByIdAndUserId(child.getId(), dev)).isEmpty();
    assertThat(profiles.findByUserId(dev)).isEmpty();
  }

  @Test
  void aUsersOverrideIsOnePerKeyAndVersion() {
    UUID u = user("override");
    String insert = """
        insert into fin_config (user_id, key, scope, value) values (:u, 'returns.expected', 'user', '{}'::jsonb)""";
    em.createNativeQuery(insert).setParameter("u", u).executeUpdate();
    assertThatThrownBy(() -> em.createNativeQuery(insert).setParameter("u", u).executeUpdate())
        .hasMessageContaining("fin_config_one_row");
  }

  @Test
  void aSystemRowCannotBeDuplicatedEither() {
    assertThatThrownBy(() -> em.createNativeQuery("""
        insert into fin_config (key, scope, value) values ('returns.expected', 'system', '{}'::jsonb)""")
        .executeUpdate()).hasMessageContaining("fin_config_one_row");
  }

  @Test
  void deletingTheUserDeletesTheirFinanceRows() {
    UUID u = user("gone");
    em.createNativeQuery("insert into fin_profile (user_id) values (:u)").setParameter("u", u).executeUpdate();
    em.createNativeQuery("insert into fin_person (user_id, display_name, kind) values (:u, 'Me', 'self')")
        .setParameter("u", u).executeUpdate();
    em.createNativeQuery("delete from users where id = :u").setParameter("u", u).executeUpdate();

    Object left = em.createNativeQuery("""
        select (select count(*) from fin_profile where user_id = :u)
             + (select count(*) from fin_person where user_id = :u)""").setParameter("u", u)
        .getSingleResult();
    assertThat(((Number) left).intValue()).isZero();
  }
}

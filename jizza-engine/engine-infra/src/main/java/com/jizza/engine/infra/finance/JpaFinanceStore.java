package com.jizza.engine.infra.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.persistence.entity.finance.FinConfigEntity;
import com.jizza.engine.persistence.entity.finance.FinConsentEntity;
import com.jizza.engine.persistence.entity.finance.FinPersonEntity;
import com.jizza.engine.persistence.entity.finance.FinProfileEntity;
import com.jizza.engine.persistence.repository.FileRepository;
import com.jizza.engine.persistence.repository.finance.FinConfigRepository;
import com.jizza.engine.persistence.repository.finance.FinConsentRepository;
import com.jizza.engine.persistence.repository.finance.FinPersonRepository;
import com.jizza.engine.persistence.repository.finance.FinProfileRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link FinanceStore} on the {@code fin_} tables. Every lookup is by owner as well as by id. */
@Service
public class JpaFinanceStore implements FinanceStore {

  private final FinProfileRepository profiles;
  private final FinPersonRepository people;
  private final FinConsentRepository consents;
  private final FinConfigRepository config;
  private final FileRepository files;

  @PersistenceContext
  private EntityManager em;

  public JpaFinanceStore(FinProfileRepository profiles, FinPersonRepository people,
                         FinConsentRepository consents, FinConfigRepository config,
                         FileRepository files) {
    this.profiles = profiles;
    this.people = people;
    this.consents = consents;
    this.config = config;
    this.files = files;
  }

  @Override
  @Transactional
  public ProfileRow ensureProfile(UUID userId, String selfName) {
    // Insert-if-missing in SQL, so two first visits at once both end with exactly one of each.
    em.createNativeQuery("""
        insert into fin_profile (user_id) values (:u) on conflict (user_id) do nothing""")
        .setParameter("u", userId).executeUpdate();
    em.createNativeQuery("""
        insert into fin_person (user_id, display_name, kind, type, is_earning)
        values (:u, :name, 'self', 'individual', false)
        on conflict (user_id) where kind = 'self' do nothing""")
        .setParameter("u", userId).setParameter("name", selfName).executeUpdate();
    em.flush();
    em.clear();
    return profiles.findByUserId(userId).map(JpaFinanceStore::row).orElseThrow();
  }

  @Override
  @Transactional
  public void ensureSelf(UUID userId, String selfName) {
    em.createNativeQuery("""
        insert into fin_person (user_id, display_name, kind, type, is_earning)
        values (:u, :name, 'self', 'individual', false)
        on conflict (user_id) where kind = 'self' do nothing""")
        .setParameter("u", userId).setParameter("name", selfName).executeUpdate();
    em.flush();
    em.clear();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ProfileRow> profile(UUID userId) {
    return profiles.findByUserId(userId).map(JpaFinanceStore::row);
  }

  @Override
  @Transactional
  public ProfileRow saveSettings(UUID userId, JsonNode settings) {
    FinProfileEntity p = profiles.findByUserId(userId)
        .orElseThrow(() -> new NotFoundException("Finance profile", userId));
    p.setSettings(settings);
    return row(profiles.saveAndFlush(p));
  }

  @Override
  @Transactional(readOnly = true)
  public List<PersonRow> people(UUID userId) {
    return people.findByUserIdOrderBySortOrderAscCreatedAtAsc(userId).stream()
        .map(JpaFinanceStore::row).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PersonRow> person(UUID userId, UUID personId) {
    return people.findByIdAndUserId(personId, userId).map(JpaFinanceStore::row);
  }

  @Override
  @Transactional
  public PersonRow createPerson(UUID userId, PersonFields fields) {
    FinPersonEntity p = new FinPersonEntity();
    p.setUserId(userId);
    apply(p, fields);
    p.setSortOrder(people.findByUserIdOrderBySortOrderAscCreatedAtAsc(userId).stream()
        .mapToInt(FinPersonEntity::getSortOrder).max().orElse(0) + 1);
    return row(people.saveAndFlush(p));
  }

  @Override
  @Transactional
  public PersonRow updatePerson(UUID userId, UUID personId, PersonFields fields) {
    FinPersonEntity p = ownedPerson(userId, personId);
    apply(p, fields);
    return row(people.saveAndFlush(p));
  }

  @Override
  @Transactional
  public PersonRow setPersonStatus(UUID userId, UUID personId, String status) {
    FinPersonEntity p = ownedPerson(userId, personId);
    p.setStatus(status);
    return row(people.saveAndFlush(p));
  }

  @Override
  @Transactional
  public PersonRow setPersonStatus(UUID userId, UUID personId, String status, LocalDate on) {
    FinPersonEntity p = ownedPerson(userId, personId);
    p.setStatus(status);
    p.setStatusOn(on);
    return row(people.saveAndFlush(p));
  }

  @Override
  @Transactional(readOnly = true)
  public List<ConsentRow> consents(UUID userId, UUID personId) {
    return consents.findByUserIdAndPersonIdOrderByGrantedAtDesc(userId, personId).stream()
        .map(JpaFinanceStore::row).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ConsentRow> allConsents(UUID userId) {
    return consents.findByUserIdOrderByGrantedAtDesc(userId).stream()
        .map(JpaFinanceStore::row).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ConsentRow> consent(UUID userId, UUID consentId) {
    return consents.findByIdAndUserId(consentId, userId).map(JpaFinanceStore::row);
  }

  @Override
  @Transactional
  public ConsentRow createConsent(UUID userId, UUID personId, String scope, String channel,
                                  UUID evidenceFileId) {
    ownedPerson(userId, personId);
    FinConsentEntity c = new FinConsentEntity();
    c.setUserId(userId);
    c.setPersonId(personId);
    c.setScope(scope);
    c.setChannel(channel);
    c.setEvidenceFileId(evidenceFileId);
    return row(consents.saveAndFlush(c));
  }

  @Override
  @Transactional
  public ConsentRow withdrawConsent(UUID userId, UUID consentId, Instant at, LocalDate purgeAfter) {
    FinConsentEntity c = consents.findByIdAndUserId(consentId, userId)
        .orElseThrow(() -> new NotFoundException("Consent note", consentId));
    c.setWithdrawnAt(at);
    c.setPurgeAfter(purgeAfter);
    return row(consents.saveAndFlush(c));
  }

  @Override
  @Transactional(readOnly = true)
  public boolean ownsFile(UUID userId, UUID fileId) {
    return files.findById(fileId).map(f -> userId.equals(f.getUserId())).orElse(false);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ConfigRow> config(UUID userId) {
    return config.findVisibleTo(userId).stream().map(JpaFinanceStore::row).toList();
  }

  @Override
  @Transactional
  public void putOverride(UUID userId, String key, LocalDate effectiveFrom, JsonNode value) {
    FinConfigEntity row = config.findByUserIdAndKey(userId, key).stream()
        .filter(c -> c.getEffectiveFrom().equals(effectiveFrom)).findFirst()
        .orElseGet(() -> {
          FinConfigEntity c = new FinConfigEntity();
          c.setUserId(userId);
          c.setKey(key);
          c.setScope("user");
          c.setEffectiveFrom(effectiveFrom);
          return c;
        });
    row.setValue(value);
    config.saveAndFlush(row);
  }

  @Override
  @Transactional
  public int deleteOverrides(UUID userId, String key) {
    var rows = config.findByUserIdAndKey(userId, key);
    config.deleteAll(rows);
    return rows.size();
  }

  private FinPersonEntity ownedPerson(UUID userId, UUID personId) {
    return people.findByIdAndUserId(personId, userId)
        .orElseThrow(() -> new NotFoundException("Person", personId));
  }

  private static void apply(FinPersonEntity p, PersonFields f) {
    p.setDisplayName(f.displayName());
    p.setKind(f.kind());
    p.setType(f.type());
    p.setDob(f.dob());
    p.setEarning(f.earning());
    p.setDependent(f.dependent());
  }

  private static ProfileRow row(FinProfileEntity p) {
    return new ProfileRow(p.getId(), p.getUserId(), p.getBaseCurrency(), p.getSettings(),
        p.getVersion());
  }

  private static PersonRow row(FinPersonEntity p) {
    return new PersonRow(p.getId(), p.getUserId(), p.getDisplayName(), p.getKind(), p.getType(),
        p.getDob(), p.isEarning(), p.isDependent(), p.getStatus(), p.getSortOrder(),
        p.getHandedOverToUserId(), p.getStatusOn());
  }

  private static ConsentRow row(FinConsentEntity c) {
    return new ConsentRow(c.getId(), c.getPersonId(), c.getScope(), c.getGrantedAt(),
        c.getWithdrawnAt(), c.getChannel(), c.getEvidenceFileId(), c.getPurgeAfter());
  }

  private static ConfigRow row(FinConfigEntity c) {
    return new ConfigRow(c.getId(), c.getUserId(), c.getKey(), c.getValue(), c.getEffectiveFrom(),
        c.getEffectiveTo(), c.getSourceUrl(), c.getVerifiedOn(), c.getVerifiedBy());
  }
}

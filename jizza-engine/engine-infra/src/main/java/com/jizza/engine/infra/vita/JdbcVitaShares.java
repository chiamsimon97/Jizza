package com.jizza.engine.infra.vita;

import com.jizza.engine.api.vita.VitaShares;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link VitaShares} on {@code vita_share}, in plain SQL. */
@Service
public class JdbcVitaShares implements VitaShares {

  private static final String COLS = """
      id, user_id, workspace_id, person_id, grantee_user_id, as_self, section, access, granted_at, ended_at,
      ended_reason""";

  private final JdbcTemplate jdbc;

  public JdbcVitaShares(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Share> activeFor(UUID granteeUserId) {
    return jdbc.query("select " + COLS + " from vita_share where grantee_user_id = ? and ended_at is null order by granted_at",
        JdbcVitaShares::row, granteeUserId);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Share> activeFor(UUID granteeUserId, UUID personId) {
    return jdbc.query("select " + COLS + " from vita_share where grantee_user_id = ? and person_id = ? and ended_at is null",
        JdbcVitaShares::row, granteeUserId, personId);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Share> activeOf(UUID ownerUserId, UUID personId) {
    return jdbc.query("select " + COLS + " from vita_share where user_id = ? and person_id = ? and ended_at is null order by granted_at",
        JdbcVitaShares::row, ownerUserId, personId);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Share> allOf(UUID ownerUserId, UUID personId) {
    return jdbc.query("select " + COLS + " from vita_share where user_id = ? and person_id = ? order by granted_at desc",
        JdbcVitaShares::row, ownerUserId, personId);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Share> activeGrantedBy(UUID ownerUserId) {
    return jdbc.query("select " + COLS + " from vita_share where user_id = ? and ended_at is null order by granted_at",
        JdbcVitaShares::row, ownerUserId);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Share> get(UUID ownerUserId, UUID shareId) {
    return jdbc.query("select " + COLS + " from vita_share where user_id = ? and id = ?", JdbcVitaShares::row,
        ownerUserId, shareId).stream().findFirst();
  }

  @Override
  @Transactional
  public Share grant(UUID ownerUserId, UUID workspaceId, UUID personId, UUID granteeUserId, boolean asSelf,
                     String section, String access) {
    jdbc.update("""
        update vita_share set ended_at = now(), ended_reason = 'replaced', updated_at = now()
         where user_id = ? and person_id = ? and grantee_user_id = ? and section = ? and ended_at is null""",
        ownerUserId, personId, granteeUserId, section);
    UUID id = jdbc.queryForObject("""
        insert into vita_share (user_id, workspace_id, person_id, grantee_user_id, as_self, section, access)
        values (?, ?, ?, ?, ?, ?, ?) returning id""", UUID.class,
        ownerUserId, workspaceId, personId, granteeUserId, asSelf, section, access);
    return get(ownerUserId, id).orElseThrow();
  }

  @Override
  @Transactional
  public boolean end(UUID ownerUserId, UUID shareId, String reason) {
    return jdbc.update("""
        update vita_share set ended_at = now(), ended_reason = ?, updated_at = now()
         where user_id = ? and id = ? and ended_at is null""", reason, ownerUserId, shareId) > 0;
  }

  @Override
  @Transactional
  public int endForPerson(UUID ownerUserId, UUID personId, String reason) {
    return jdbc.update("""
        update vita_share set ended_at = now(), ended_reason = ?, updated_at = now()
         where user_id = ? and person_id = ? and ended_at is null""", reason, ownerUserId, personId);
  }

  @Override
  @Transactional
  public int endBetween(UUID userA, UUID userB, String reason) {
    return jdbc.update("""
        update vita_share set ended_at = now(), ended_reason = ?, updated_at = now()
         where ended_at is null and ((user_id = ? and grantee_user_id = ?) or (user_id = ? and grantee_user_id = ?))""",
        reason, userA, userB, userB, userA);
  }

  @Override
  @Transactional
  public int endInWorkspace(UUID workspaceId, UUID userId, String reason) {
    return jdbc.update("""
        update vita_share set ended_at = now(), ended_reason = ?, updated_at = now()
         where ended_at is null and workspace_id = ? and (user_id = ? or grantee_user_id = ?)""",
        reason, workspaceId, userId, userId);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Consent> healthConsents(UUID ownerUserId, UUID personId) {
    return jdbc.query("""
        select id, person_id, scope, channel, granted_at, withdrawn_at from fin_consent
         where user_id = ? and person_id = ? and domain = 'health' order by granted_at desc""",
        JdbcVitaShares::consent, ownerUserId, personId);
  }

  @Override
  @Transactional
  public Consent recordHealthConsent(UUID ownerUserId, UUID personId, String scope, String channel) {
    return jdbc.query("""
        insert into fin_consent (user_id, person_id, scope, channel, domain) values (?, ?, ?, ?, 'health')
        returning id, person_id, scope, channel, granted_at, withdrawn_at""",
        JdbcVitaShares::consent, ownerUserId, personId, scope, channel).get(0);
  }

  @Override
  @Transactional
  public boolean withdrawHealthConsent(UUID ownerUserId, UUID consentId) {
    return jdbc.update("""
        update fin_consent set withdrawn_at = now(), updated_at = now()
         where user_id = ? and id = ? and domain = 'health' and withdrawn_at is null""", ownerUserId, consentId) > 0;
  }

  @Override
  @Transactional(readOnly = true)
  public List<CareNote> careNotes(UUID ownerUserId, UUID personId, int limit) {
    return jdbc.query("""
        select id, person_id, text, written_by_user_id, written_at from vita_care_note
         where user_id = ? and person_id = ? order by written_at desc, id limit ?""",
        JdbcVitaShares::note, ownerUserId, personId, limit);
  }

  @Override
  @Transactional
  public CareNote addCareNote(UUID ownerUserId, UUID personId, String text, UUID writtenByUserId) {
    return jdbc.query("""
        insert into vita_care_note (user_id, person_id, text, written_by_user_id) values (?, ?, ?, ?)
        returning id, person_id, text, written_by_user_id, written_at""",
        JdbcVitaShares::note, ownerUserId, personId, text, writtenByUserId).get(0);
  }

  @Override
  @Transactional
  public int deleteCareNotes(UUID ownerUserId, UUID personId) {
    return jdbc.update("delete from vita_care_note where user_id = ? and person_id = ?", ownerUserId, personId);
  }

  private static Consent consent(ResultSet rs, int i) throws SQLException {
    Timestamp w = rs.getTimestamp("withdrawn_at");
    return new Consent(rs.getObject("id", UUID.class), rs.getObject("person_id", UUID.class), rs.getString("scope"),
        rs.getString("channel"), rs.getTimestamp("granted_at").toInstant(), w == null ? null : w.toInstant());
  }

  private static CareNote note(ResultSet rs, int i) throws SQLException {
    return new CareNote(rs.getObject("id", UUID.class), rs.getObject("person_id", UUID.class), rs.getString("text"),
        rs.getObject("written_by_user_id", UUID.class), rs.getTimestamp("written_at").toInstant());
  }

  private static Share row(ResultSet rs, int i) throws SQLException {
    Timestamp ended = rs.getTimestamp("ended_at");
    return new Share(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
        rs.getObject("workspace_id", UUID.class), rs.getObject("person_id", UUID.class),
        rs.getObject("grantee_user_id", UUID.class), rs.getBoolean("as_self"), rs.getString("section"),
        rs.getString("access"), rs.getTimestamp("granted_at").toInstant(), ended == null ? null : ended.toInstant(),
        rs.getString("ended_reason"));
  }
}

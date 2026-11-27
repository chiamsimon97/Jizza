package com.jizza.engine.infra.vita;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaStore;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link VitaStore} on {@code vita_config} and {@code vita_profile}, in plain SQL. */
@Service
public class JdbcVitaStore implements VitaStore {

  private final JdbcTemplate jdbc;

  public JdbcVitaStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ConfigRow> config(UUID userId) {
    return jdbc.query("""
        select id, user_id, key, value::text, effective_from, effective_to, source_url, verified_on,
               verified_by
          from vita_config where user_id is null or user_id = ? order by key, effective_from""",
        JdbcVitaStore::row, userId);
  }

  @Override
  @Transactional
  public void putOverride(UUID userId, String key, LocalDate effectiveFrom, JsonNode value) {
    jdbc.update("""
        insert into vita_config (user_id, key, scope, value, effective_from)
        values (?, ?, 'user', cast(? as jsonb), ?)
        on conflict (key, user_id, effective_from)
        do update set value = excluded.value, updated_at = now()""",
        userId, key, Json.write(value), Date.valueOf(effectiveFrom));
  }

  @Override
  @Transactional
  public int deleteOverrides(UUID userId, String key) {
    return jdbc.update("delete from vita_config where user_id = ? and key = ?", userId, key);
  }

  @Override
  @Transactional
  public UUID ensureProfile(UUID userId, UUID personId, UUID createdBy) {
    // Insert-if-missing in SQL, so two first visits at once both end with exactly one row. The
    // person must be the owner's: the select refuses anyone else's.
    jdbc.update("""
        insert into vita_profile (user_id, person_id, created_by_user_id)
        select p.user_id, p.id, ? from fin_person p where p.id = ? and p.user_id = ?
        on conflict (person_id) do nothing""", createdBy, personId, userId);
    return jdbc.queryForList("select id from vita_profile where person_id = ? and user_id = ?",
            UUID.class, personId, userId)
        .stream().findFirst().orElseThrow(() -> new NotFoundException("Person", personId));
  }

  private static ConfigRow row(ResultSet rs, int n) throws SQLException {
    Date to = rs.getDate("effective_to");
    Date verified = rs.getDate("verified_on");
    return new ConfigRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
        rs.getString("key"), Json.read(rs.getString("value")), rs.getDate("effective_from").toLocalDate(),
        to == null ? null : to.toLocalDate(), rs.getString("source_url"),
        verified == null ? null : verified.toLocalDate(), rs.getString("verified_by"));
  }
}

package com.jizza.engine.infra.vita;

import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.engine.infra.records.JdbcOwnedRecords;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link VitaRecords}: the owner-scoped records store over Vita's person-owned tables. */
@Component
public class JdbcVitaRecords extends JdbcOwnedRecords implements VitaRecords {

  /** Vita's person-owned tables, in export order. Later phases add theirs here. */
  public static final List<String> TABLES_IN_ORDER = List.of(
      "vita_profile", "vita_emergency_contact", "vita_condition", "vita_allergy", "vita_measurement");

  public JdbcVitaRecords(JdbcTemplate jdbc) {
    super(jdbc, TABLES_IN_ORDER, "Vita");
  }

  @Override
  public List<String> tables() {
    return TABLES_IN_ORDER;
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> ownersWithRecords() {
    return jdbc().queryForList("select distinct user_id from vita_profile order by user_id", UUID.class);
  }
}

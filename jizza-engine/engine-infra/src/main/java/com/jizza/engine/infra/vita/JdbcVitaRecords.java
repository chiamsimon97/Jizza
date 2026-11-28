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
      "vita_profile", "vita_emergency_contact", "vita_doctor", "vita_condition", "vita_allergy",
      "vita_measurement", "vita_visit", "vita_usual_range", "vita_medicine", "vita_medicine_schedule",
      "vita_medicine_event", "vita_dose_log", "vita_reminder", "vita_document",
      "vita_lab_report", "vita_lab_result", "vita_check",
      "vita_vaccination", "vita_event", "vita_mail_source", "vita_policy_detail", "vita_claim",
      "vita_journal_entry", "vita_goal", "vita_goal_checkin", "vita_connector_grant", "vita_weekly_note");

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
    return jdbc().queryForList("""
        select user_id from vita_profile union select user_id from vita_medicine
        union select user_id from vita_visit union select user_id from vita_reminder
        union select user_id from vita_mail_source
        union select user_id from fin_insurance_policy where kind in
          ('health', 'super_topup', 'critical_illness', 'personal_accident', 'government_scheme')
        order by 1""", UUID.class);
  }
}

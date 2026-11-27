package com.jizza.platform.usecase.vita.measure;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.NotificationPriority;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.config.VitaConfigService;
import com.jizza.platform.usecase.vita.ranges.RedFlagRule;
import com.jizza.platform.usecase.vita.ranges.TargetEvaluator;
import com.jizza.platform.usecase.vita.ranges.TargetEvaluator.Evaluation;
import com.jizza.platform.usecase.vita.ranges.VitaTargetService;
import com.jizza.platform.usecase.vita.safety.VitaSafetyService;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import com.jizza.platform.usecase.vita.tools.VitaToolProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What happens after a reading raises a red flag (spec 5.1, 8.2, Fixture B). A typed reading asks
 * "Is 182/112 right?" first; once the user confirms, the manager is alerted (from Phase 2a also
 * helpers with access to vitals). A yes to the rule's question raises urgent to emergency. The alert
 * carries no value and no diagnosis: only the person, the level and a link to the reading.
 */
@Service
public class VitaRedFlagService {

  /** Product choice when config holds no value: readings this close with the same numbers are one. */
  static final int DEFAULT_DUPLICATE_MINUTES = 10;

  private final VitaRecords records;
  private final VitaScopeResolver scopes;
  private final VitaConfigService config;
  private final ObjectProvider<VitaTargetService> targets;
  private final ObjectProvider<VitaSafetyService> safety;
  private final NotificationBus bus;
  private final AgentDirectory agents;
  private final AuditLog audit;

  public VitaRedFlagService(VitaRecords records, VitaScopeResolver scopes, VitaConfigService config,
                            ObjectProvider<VitaTargetService> targets, ObjectProvider<VitaSafetyService> safety,
                            NotificationBus bus, AgentDirectory agents, AuditLog audit) {
    this.records = records;
    this.scopes = scopes;
    this.config = config;
    this.targets = targets;
    this.safety = safety;
    this.bus = bus;
    this.agents = agents;
    this.audit = audit;
  }

  public int duplicateWindowMinutes(UUID owner) {
    return config.list(owner).stream().filter(i -> i.key().equals("entry.duplicate_window_minutes"))
        .findFirst().map(i -> i.value() == null ? DEFAULT_DUPLICATE_MINUTES : i.value().path("minutes").asInt(DEFAULT_DUPLICATE_MINUTES))
        .orElse(DEFAULT_DUPLICATE_MINUTES);
  }

  /**
   * The user's answer to "Is 182/112 right?" and to the rule's question. {@code right = false}
   * alerts nobody: the user corrects or deletes the reading instead.
   */
  @Transactional
  public ObjectNode confirm(UUID caller, UUID personId, UUID id, boolean right, Boolean signsNow) {
    PersonScope p = scopes.person(caller, personId);
    ObjectNode row = records.get("vita_measurement", p.owner(), id)
        .filter(r -> p.personId().toString().equals(r.path("personId").asText()))
        .filter(r -> r.path("deletedAt").isNull())
        .orElseThrow(() -> new NotFoundException("Reading", id));
    Evaluation e = VitaMeasurementUseCase.evaluationOf(row, targets.getObject().context(p), signsNow);
    if (e == null || !e.redFlagged()) throw new ValidationException("This reading is not a red flag.");
    ObjectNode out = row.deepCopy();
    out.set("evaluation", Json.toNode(e));
    if (!right) {
      out.put("alerted", false);
      out.put("note", "Nobody was alerted. Correct the reading or delete it.");
      return out;
    }
    out.put("alerted", alert(p, row, e));
    out.set("help", safety.getObject().help(caller, p.personId(), e.redFlag().level(), e.redFlag().ruleId()));
    return out;
  }

  /**
   * Alerts the manager once per reading and level. Returns whether an alert was raised now (false
   * when it was already sent at this level).
   */
  @Transactional
  public boolean alert(PersonScope p, ObjectNode row, Evaluation e) {
    String level = e.redFlag().level();
    ObjectNode detail = row.hasNonNull("detail") && row.get("detail").isObject()
        ? (ObjectNode) row.get("detail").deepCopy() : Json.obj();
    if (level.equals(detail.path("redFlag").path("level").asText(null))) return false;
    UUID vita = agents.byKey(p.owner(), VitaToolProvider.AGENT_KEY).map(a -> a.id()).orElse(null);
    String what = switch (row.path("kind").asText()) {
      case "bp" -> "blood pressure reading";
      case "glucose" -> "sugar reading";
      default -> "reading";
    };
    String body = p.person().displayName() + ": a " + what + (RedFlagRule.EMERGENCY.equals(level)
        ? " needs help now. Call the emergency number." : " needs attention today.");
    ObjectNode ctx = Json.obj().put("type", "vita_red_flag").put("personId", p.personId().toString())
        .put("measurementId", row.path("id").asText()).put("level", level)
        .put("rule", e.redFlag().ruleId());
    for (UUID recipient : recipients(p)) {
      bus.raise(NotificationKind.INFO, vita, new NotificationContext(recipient, body, ctx, null, null, null,
          Duration.ofDays(2), NotificationPriority.URGENT));
    }
    detail.set("redFlag", Json.obj().put("level", level).put("rule", e.redFlag().ruleId())
        .put("confirmedAt", Instant.now().toString()));
    records.update("vita_measurement", p.owner(), java.util.UUID.fromString(row.path("id").asText()),
        Json.obj().set("detail", detail));
    audit.record(p.owner(), ActorType.USER, p.caller(), "vita.red_flag.alerted", row.path("id").asText(), null);
    return true;
  }

  /** Who hears of a red flag: the manager until sharing arrives (Phase 2a). */
  List<UUID> recipients(PersonScope p) {
    return List.of(p.owner());
  }

  /** Whether the latest reading of a kind is a red flag from the last hours (for Home's banners). */
  static boolean recent(ObjectNode row, int hours) {
    return row.hasNonNull("takenAt")
        && Instant.parse(row.get("takenAt").asText()).isAfter(Instant.now().minus(Duration.ofHours(hours)))
        && TargetEvaluator.RED_FLAG.equals(row.path("evaluation").path("status").asText());
  }
}

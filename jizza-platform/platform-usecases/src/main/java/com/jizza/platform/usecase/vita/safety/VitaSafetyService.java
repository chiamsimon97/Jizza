package com.jizza.platform.usecase.vita.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.platform.usecase.vita.config.VitaConfigService;
import com.jizza.platform.usecase.vita.config.VitaConfigService.ConfigItem;
import com.jizza.platform.usecase.vita.profile.VitaProfileUseCase;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The help block Vita shows first when something may be an emergency (spec 8.2): "Call now"
 * (emergency) with the emergency number and the person's preferred hospital, "Act today" (urgent)
 * with the rule's advice, and the self-harm reply with the helpline. Every word and number comes
 * from {@code vita_config}, with its verification state; nothing here names a diagnosis, and no
 * one else is alerted from here.
 */
@Service
public class VitaSafetyService {

  public static final List<String> LEVELS = List.of("emergency", "urgent", "self_harm");
  public static final List<String> RULES = List.of("bp", "low_sugar", "high_sugar", "spo2", "temperature", "chat_words");

  private final VitaScopeResolver scopes;
  private final VitaConfigService config;
  private final VitaProfileUseCase profiles;

  public VitaSafetyService(VitaScopeResolver scopes, VitaConfigService config, VitaProfileUseCase profiles) {
    this.scopes = scopes;
    this.config = config;
    this.profiles = profiles;
  }

  /**
   * The help block for a level. {@code rule} (optional) picks the red-flag rule whose advice and
   * question to show; a rule that is not set says so instead of giving a number.
   */
  public ObjectNode help(UUID caller, UUID personId, String level, String rule) {
    if (level == null || !LEVELS.contains(level)) {
      throw new ValidationException("level must be one of " + String.join(", ", LEVELS) + ".");
    }
    var n = JsonNodeFactory.instance;
    ObjectNode out = n.objectNode().put("level", level);
    ConfigItem numbers = config.item(caller, "emergency.numbers");
    out.set("emergencyNumbers", numbers.value());
    out.put("emergencyNumbersNeedVerification", numbers.needsVerification());

    if ("self_harm".equals(level)) {
      ConfigItem sh = config.item(caller, "clinical.redflags.self_harm");
      out.set("helpline", sh.value());
      out.put("needsVerification", sh.needsVerification());
      out.put("instructions", "Reply calmly and kindly with the helpline and the emergency number. "
          + "Do not alert anyone else unless the person agrees. Do not lecture or diagnose.");
      return out;
    }

    // A helper who sees the readings but not the emergency card gets the person's name only.
    PersonScope p;
    boolean card = true;
    try {
      p = scopes.personOrSelf(caller, personId, com.jizza.platform.usecase.vita.sharing.VitaSection.EMERGENCY, false);
    } catch (com.jizza.engine.api.error.NotFoundException noCard) {
      p = scopes.personOrSelf(caller, personId, com.jizza.platform.usecase.vita.sharing.VitaSection.VITALS, false);
      card = false;
    }
    out.put("personName", p.person().displayName());
    if (card) {
      ObjectNode profile = profiles.profileRow(p);
      out.set("preferredHospital", profile.get("preferredHospital"));
      out.set("preferredHospitalPhone", profile.get("preferredHospitalPhone"));
      out.put("emergencyCard", "/api/vita/people/" + p.personId() + "/emergency-card");
    }
    out.put("wording", "emergency".equals(level)
        ? "Call now. This is at a level where doctors advise emergency care."
        : "Act today. This is at a level where doctors advise urgent care.");

    if (rule != null && !rule.isBlank()) {
      if (!RULES.contains(rule)) throw new ValidationException("rule must be one of " + String.join(", ", RULES) + ".");
      ConfigItem r = config.item(caller, "clinical.redflags." + rule);
      ObjectNode ruleNode = out.putObject("rule").put("key", r.key()).put("state", r.state()).put("label", r.label());
      if (r.value() == null) {
        ruleNode.put("note", "Not set: there is no source for this rule yet. Ask the person's doctor.");
      } else {
        JsonNode v = r.value();
        ruleNode.set("value", v);
        if ("emergency".equals(level) && v.has("emergencyAdvice")) out.set("advice", v.get("emergencyAdvice"));
        else if (v.has("advice")) out.set("advice", v.get("advice"));
        if (v.has("question")) out.set("question", v.get("question"));
        if (v.has("signs")) out.set("signs", v.get("signs"));
      }
    }
    out.put("instructions", "Show this help first, before anything else. Use these words, never a "
        + "diagnosis. Do not suggest starting, stopping or changing any medicine.");
    return out;
  }
}

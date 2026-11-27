package com.jizza.platform.usecase.vita.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.autonomy.ActionTypeSource;
import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.vita.config.VitaConfigService;
import com.jizza.platform.usecase.vita.measure.VitaMeasurementUseCase;
import com.jizza.platform.usecase.vita.profile.VitaProfileUseCase;
import com.jizza.platform.usecase.vita.quickstart.VitaQuickStartUseCase;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.records.VitaTable;
import com.jizza.platform.usecase.vita.safety.VitaSafetyService;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Vita's own tools (spec 6.1), Phase 0: read a person's record, the help block for a red flag,
 * open a Health view, and put a filled-in form in the chat. Only the agent whose key is
 * {@code health} gets them, only while {@code jizza.health.enabled} is on, and only on the
 * caller's own household. No tool saves, sends or shares anything: a form is saved only when the
 * user presses Save on the card, through the same endpoint as the Health tab.
 *
 * <p>Every tool's action type is registered here, so a read is never held for approval (an
 * unregistered tool counts as MONEY). Arguments are kept out of the audit log: they carry health
 * details.
 */
@Component
public class VitaToolProvider implements ToolProvider, ActionTypeSource {

  public static final String AGENT_KEY = "health";
  public static final String OVERVIEW = "vita_overview";
  public static final String READ = "vita_read";
  public static final String RED_FLAG_HELP = "vita_red_flag_help";
  public static final String OPEN_SCREEN = "vita_open_screen";
  public static final String SHOW_FORM = "vita_show_form";
  /** The chat card a form goes out as. */
  public static final String FORM_CARD = "vita_form";

  public static final List<String> FORMS = List.of("profile", "emergency_contact");
  static final List<String> SECTIONS = List.of("people", "profile", "emergency_card", "conditions",
      "allergies", "emergency_contacts", "weight", "settings");
  static final List<String> SCREENS = List.of("home", "conditions", "allergies", "emergency-card",
      "profile", "quick-start", "settings");

  static final Map<String, ActionType> TYPES = Map.of(
      OVERVIEW, ActionType.READ, READ, ActionType.READ, RED_FLAG_HELP, ActionType.READ,
      OPEN_SCREEN, ActionType.DRAFT, SHOW_FORM, ActionType.DRAFT);

  private final AgentDirectory agents;
  private final ObjectProvider<VitaScopeResolver> scopes;
  private final ObjectProvider<VitaProfileUseCase> profiles;
  private final ObjectProvider<VitaRecordsUseCase> forms;
  private final ObjectProvider<VitaMeasurementUseCase> readings;
  private final ObjectProvider<VitaQuickStartUseCase> quickStart;
  private final ObjectProvider<VitaConfigService> config;
  private final ObjectProvider<VitaSafetyService> safety;
  private final boolean enabled;
  private final List<ToolSpec> specs;

  public VitaToolProvider(AgentDirectory agents, ObjectProvider<VitaScopeResolver> scopes,
                          ObjectProvider<VitaProfileUseCase> profiles,
                          ObjectProvider<VitaRecordsUseCase> forms,
                          ObjectProvider<VitaMeasurementUseCase> readings,
                          ObjectProvider<VitaQuickStartUseCase> quickStart,
                          ObjectProvider<VitaConfigService> config,
                          ObjectProvider<VitaSafetyService> safety,
                          @Value("${jizza.health.enabled:false}") boolean enabled) {
    this.agents = agents;
    this.scopes = scopes;
    this.profiles = profiles;
    this.forms = forms;
    this.readings = readings;
    this.quickStart = quickStart;
    this.config = config;
    this.safety = safety;
    this.enabled = enabled;
    ObjectNode person = JsonSchemas.string("A person's id from vita_overview; leave out for the user themself");
    this.specs = List.of(
        new ToolSpec(OVERVIEW, "Today's picture of the household's health records: each person "
            + "(with their id), how far their Quick start is, their latest weight, active "
            + "conditions, allergies and emergency contacts on file. Read only.",
            JsonSchemas.object(props("personId", person), List.of()), false, false),
        new ToolSpec(READ, "One section of one person's health record, exactly as stored. "
            + "Sections: people, profile, emergency_card, conditions, allergies, "
            + "emergency_contacts, weight, settings (Vita's defaults with their state: "
            + "needs_verification, verified or not_set). Quote it; do not add figures of your own.",
            JsonSchemas.object(props("personId", person, "section", enumOf("Which section", SECTIONS)),
                List.of("section")), false, false),
        new ToolSpec(RED_FLAG_HELP, "The help block to show FIRST when something may be an "
            + "emergency: level emergency (call now: emergency number, the person's preferred "
            + "hospital and phone, their emergency card), urgent (act today: the rule's advice and "
            + "its question) or self_harm (the helpline). Use it whenever a reading or a message "
            + "may be a red flag, or when you are unsure whether it is happening now.",
            JsonSchemas.object(props("personId", person,
                "level", enumOf("emergency, urgent or self_harm", VitaSafetyService.LEVELS),
                "rule", enumOf("Optional: the red-flag rule (bp, low_sugar, chat_words, ...)", VitaSafetyService.RULES)),
                List.of("level")), false, false),
        new ToolSpec(OPEN_SCREEN, "Give the user a link card into a view of the Health tab. Changes nothing.",
            JsonSchemas.object(props("personId", person, "screen", enumOf("The view to open", SCREENS)),
                List.of("screen")), false, false),
        new ToolSpec(SHOW_FORM, "Put a short form in the chat for the user to check and save: "
            + "profile (blood group, height, sex at birth, date of birth, organ donor, preferred "
            + "hospital and its phone) or emergency_contact (name, relation, phone). Use it whenever "
            + "the user tells you one of these: fill in what they said and leave the rest. Say in a "
            + "sentence what to check, then end your turn. Nothing is saved until the user presses "
            + "Save on the card; you never save it yourself.",
            JsonSchemas.object(props("personId", person,
                "form", enumOf("Which form", FORMS),
                "title", JsonSchemas.string("Optional heading"),
                "intro", JsonSchemas.string("Optional: one sentence above the fields"),
                "values", JsonSchemas.objectSchema("What you know, by field. profile: bloodGroup "
                    + "(a_pos, a_neg, b_pos, b_neg, ab_pos, ab_neg, o_pos, o_neg, unknown), heightCm, "
                    + "sexAtBirth (female, male, intersex, unknown), dob (yyyy-mm-dd), organDonor "
                    + "(true/false), preferredHospital, preferredHospitalPhone. emergency_contact: "
                    + "name, relation, phone.")),
                List.of("form")), false, false));
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    return vita(agentId).isPresent() ? specs : List.of();
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return TYPES.containsKey(toolName) && vita(agentId).isPresent();
  }

  @Override
  public boolean redactArguments(String toolName) {
    return true;
  }

  @Override
  public Optional<ActionType> actionTypeOf(UUID agentId, String toolRef) {
    return Optional.ofNullable(TYPES.get(toolRef));
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    Optional<AgentDto> vita = vita(agentId);
    if (vita.isEmpty()) return ToolResult.error("Only Vita has the health tools");
    UUID user = vita.get().userId();
    JsonNode args = call.args() == null ? Json.obj() : call.args();
    try {
      return switch (call.name()) {
        case OVERVIEW -> ToolResult.ok(overview(user, uuid(args, "personId")));
        case READ -> ToolResult.ok(read(user, uuid(args, "personId"), args.path("section").asText()));
        case RED_FLAG_HELP -> ToolResult.ok(safety.getObject().help(user, uuid(args, "personId"),
            args.path("level").asText(null), args.hasNonNull("rule") ? args.get("rule").asText() : null));
        case OPEN_SCREEN -> ToolResult.ok(openScreen(vita.get(), uuid(args, "personId"), args.path("screen").asText("home")));
        case SHOW_FORM -> showForm(user, args);
        default -> ToolResult.error("Unknown health tool " + call.name());
      };
    } catch (ValidationException | NotFoundException e) {
      return ToolResult.error(e.getMessage());
    }
  }

  private JsonNode overview(UUID user, UUID personId) {
    ObjectNode out = Json.obj();
    ArrayNode people = out.putArray("people");
    var list = personId == null ? profiles.getObject().people(user).stream().map(v -> v.id()).toList() : List.of(personId);
    for (UUID id : list) {
      PersonScope p = scopes.getObject().person(user, id);
      ObjectNode n = people.addObject().put("id", id.toString()).put("name", p.person().displayName())
          .put("kind", p.person().kind()).put("recordOpen", p.open());
      var qs = quickStart.getObject().get(user, id).progress();
      n.put("quickStartDone", qs.done()).put("quickStartNext", qs.next());
      readings.getObject().latest(p, "weight").ifPresent(w -> n.putObject("latestWeight")
          .put("kg", w.path("value").decimalValue()).put("takenAt", w.path("takenAt").asText()));
      ArrayNode conditions = n.putArray("activeConditions");
      forms.getObject().live(p, VitaTable.CONDITION).stream()
          .filter(c -> "active".equals(c.path("status").asText()))
          .forEach(c -> conditions.add(c.path("name").asText()));
      ArrayNode allergies = n.putArray("allergies");
      forms.getObject().live(p, VitaTable.ALLERGY).forEach(a -> allergies.add(a.path("substance").asText()));
      n.put("emergencyContacts", forms.getObject().live(p, VitaTable.CONTACT).size());
    }
    out.put("note", "Medicines, blood pressure, sugar and other readings arrive in the next update. "
        + "Never diagnose; never suggest starting, stopping or changing a medicine.");
    return out;
  }

  private JsonNode read(UUID user, UUID personId, String section) {
    if (!SECTIONS.contains(section)) {
      throw new ValidationException("section must be one of " + String.join(", ", SECTIONS) + ".");
    }
    if ("people".equals(section)) return Json.toNode(profiles.getObject().people(user));
    if ("settings".equals(section)) return Json.toNode(config.getObject().list(user));
    UUID id = personId == null ? scopes.getObject().self(user).personId() : personId;
    return switch (section) {
      case "profile" -> Json.toNode(profiles.getObject().get(user, id));
      case "emergency_card" -> profiles.getObject().emergencyCard(user, id);
      case "conditions" -> Json.toNode(forms.getObject().list(user, id, VitaTable.CONDITION));
      case "allergies" -> Json.toNode(forms.getObject().list(user, id, VitaTable.ALLERGY));
      case "emergency_contacts" -> Json.toNode(forms.getObject().list(user, id, VitaTable.CONTACT));
      case "weight" -> Json.toNode(readings.getObject().list(user, id, "weight"));
      default -> throw new IllegalStateException(section);
    };
  }

  private JsonNode openScreen(AgentDto vita, UUID personId, String screen) {
    if (!SCREENS.contains(screen)) throw new ValidationException("screen must be one of " + String.join(", ", SCREENS));
    String url = "/agents/" + vita.id() + "?tab=health&view=" + screen;
    if (personId != null) url += "&person=" + scopes.getObject().person(vita.userId(), personId).personId();
    return Json.obj().put("type", "link").put("label", "Open " + screen.replace('-', ' ')).put("url", url);
  }

  /**
   * Checks what Vita filled in field by field, with the same rules as the Health tab's own forms,
   * and puts the card on the reply. A value that would not pass is left out and named back to
   * Vita. Nothing is written here.
   */
  private ToolResult showForm(UUID user, JsonNode args) {
    if (ChatScope.current().isEmpty()) {
      return ToolResult.error("There is no chat to show a form in. Point the user to the Health tab instead.");
    }
    String form = args.path("form").asText();
    if (!FORMS.contains(form)) return ToolResult.error("form must be one of " + String.join(", ", FORMS));
    PersonScope p = scopes.getObject().personOrSelf(user, uuid(args, "personId"));
    ObjectNode values = Json.obj();
    ArrayNode dropped = Json.mapper().createArrayNode();
    var it = args.path("values").fields();
    while (it.hasNext()) {
      var e = it.next();
      String field = e.getKey();
      if (!allowed(form, field) || e.getValue().isNull()) {
        dropped.add(field + ": not on this form");
        continue;
      }
      try {
        values.set(field, checked(form, field, e.getValue()));
      } catch (ValidationException bad) {
        dropped.add(field + ": " + bad.getMessage());
      }
    }
    ObjectNode payload = Json.obj().put("id", UUID.randomUUID().toString()).put("form", form)
        .put("personId", p.personId().toString()).put("personName", p.person().displayName());
    String title = clip(args.path("title").asText(""), 80);
    String intro = clip(args.path("intro").asText(""), 300);
    if (!title.isBlank()) payload.put("title", title);
    if (!intro.isBlank()) payload.put("intro", intro);
    payload.set("values", values);
    ChatScope.record(new UiAction(FORM_CARD, payload));
    ObjectNode out = Json.obj().put("shown", true).put("note", "The form is in the chat with what you "
        + "filled in. Say in a sentence what the user should check, then end your turn. It is saved "
        + "only when they press Save.");
    if (!dropped.isEmpty()) out.set("leftOut", dropped);
    return ToolResult.ok(out);
  }

  /** The profile fields a chat form may carry: never the ABHA number. */
  static final List<String> PROFILE_FORM = List.of("bloodGroup", "heightCm", "sexAtBirth", "dob",
      "organDonor", "preferredHospital", "preferredHospitalPhone");

  static boolean allowed(String form, String field) {
    return "profile".equals(form) ? PROFILE_FORM.contains(field) : VitaTable.CONTACT.fields.containsKey(field)
        && !"sortOrder".equals(field);
  }

  static JsonNode checked(String form, String field, JsonNode v) {
    if ("emergency_contact".equals(form)) {
      return VitaRecordsUseCase.clean(VitaTable.CONTACT.fields, Json.obj().set(field, v), false).get(field);
    }
    return switch (field) {
      case "heightCm" -> VitaProfileUseCase.checkHeight(v);
      case "dob" -> Json.mapper().getNodeFactory().textNode(VitaRecordsUseCase.pastDate("dob", v).toString());
      default -> VitaRecordsUseCase.clean(VitaProfileUseCase.FIELDS, Json.obj().set(field, v), false).get(field);
    };
  }

  private static UUID uuid(JsonNode args, String field) {
    if (!args.hasNonNull(field) || args.get(field).asText().isBlank()) return null;
    try {
      return UUID.fromString(args.get(field).asText());
    } catch (IllegalArgumentException e) {
      throw new ValidationException(field + " must be an id.");
    }
  }

  private static String clip(String s, int max) {
    String t = s == null ? "" : s.strip();
    return t.length() > max ? t.substring(0, max) : t;
  }

  private Optional<AgentDto> vita(UUID agentId) {
    if (!enabled || agentId == null) return Optional.empty();
    return agents.byId(agentId).filter(a -> AGENT_KEY.equals(a.key()));
  }

  private static Map<String, JsonNode> props(Object... pairs) {
    Map<String, JsonNode> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], (JsonNode) pairs[i + 1]);
    return map;
  }

  private static ObjectNode enumOf(String description, List<String> values) {
    ObjectNode node = JsonSchemas.string(description);
    ArrayNode allowed = node.putArray("enum");
    values.forEach(allowed::add);
    return node;
  }
}

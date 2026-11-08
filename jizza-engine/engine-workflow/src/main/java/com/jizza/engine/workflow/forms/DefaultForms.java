package com.jizza.engine.workflow.forms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.RateLimitedException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.form.Forms;
import com.jizza.engine.api.queue.JobQueue;
import com.jizza.engine.api.queue.JobTypes;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.persistence.entity.FormEntity;
import com.jizza.engine.persistence.entity.FormSubmissionEntity;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.repository.FormRepository;
import com.jizza.engine.persistence.repository.FormSubmissionRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forms and their submissions. A submission is kept first and its workflows start from a queued
 * job, so the person filling in the form gets their thank-you at once and a restart loses nothing.
 *
 * <p>Only workflows that are on start, as with every unattended trigger. A field made from a
 * workflow step points that step's setting at the run's input for that run only; what was
 * submitted is data and is never read as a formula.
 */
@Service
public class DefaultForms implements Forms {

  private static final Logger log = LoggerFactory.getLogger(DefaultForms.class);
  /** A submission's workflows, started after it is saved. */
  public static final String JOB_TYPE = JobTypes.FORM_SUBMITTED;
  private static final int PER_CLIENT_PER_MINUTE = 10;
  private static final int PER_FORM_PER_MINUTE = 120;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final FormRepository forms;
  private final FormSubmissionRepository submissions;
  private final WorkflowRepository workflows;
  private final ObjectProvider<WorkflowEngine> engine;
  private final ObjectProvider<JobQueue> queue;
  private final JexlSandbox sandbox;
  private final AuditLog audit;
  private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

  public DefaultForms(FormRepository forms, FormSubmissionRepository submissions,
                      WorkflowRepository workflows, ObjectProvider<WorkflowEngine> engine,
                      ObjectProvider<JobQueue> queue, JexlSandbox sandbox, AuditLog audit) {
    this.forms = forms;
    this.submissions = submissions;
    this.workflows = workflows;
    this.engine = engine;
    this.queue = queue;
    this.sandbox = sandbox;
    this.audit = audit;
  }

  @PostConstruct
  void register() {
    JobQueue q = queue.getIfAvailable();
    if (q != null) q.register(JOB_TYPE, job -> startWorkflows(UUID.fromString(job.payload().path("submissionId").asText())));
  }

  // ── The builder ──────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public List<FormDto> list(UUID userId) {
    return forms.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(this::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public FormDto get(UUID userId, UUID formId) {
    return toDto(owned(userId, formId));
  }

  @Override
  @Transactional
  public FormDto create(UUID userId, FormDraft draft) {
    FormEntity form = new FormEntity();
    form.setUserId(userId);
    form.setStatus("draft");
    apply(userId, form, draft);
    if (draft.sourceWorkflowId() != null) {
      ownedWorkflow(userId, draft.sourceWorkflowId());
      form.setSourceWorkflowId(draft.sourceWorkflowId());
    }
    forms.save(form);
    audit.record(userId, ActorType.USER, null, "form.created", form.getName(),
        Json.obj().put("formId", form.getId().toString()));
    return toDto(form);
  }

  @Override
  @Transactional
  public FormDto update(UUID userId, UUID formId, FormDraft draft) {
    FormEntity form = owned(userId, formId);
    apply(userId, form, draft);
    forms.save(form);
    return toDto(form);
  }

  @Override
  @Transactional
  public void delete(UUID userId, UUID formId) {
    FormEntity form = owned(userId, formId);
    for (WorkflowEntity w : workflows.findByFormId(formId)) {
      w.setFormId(null);
      workflows.save(w);
    }
    forms.delete(form);
    audit.record(userId, ActorType.USER, null, "form.deleted", form.getName(),
        Json.obj().put("formId", formId.toString()));
  }

  @Override
  @Transactional
  public FormDto publish(UUID userId, UUID formId) {
    FormEntity form = owned(userId, formId);
    if (FormFields.flatten(form.getFields()).stream().noneMatch(f ->
        !FormFields.pending(f) && FormFields.INPUT_TYPES.contains(f.path("type").asText()))) {
      throw new ValidationException("Add at least one field people can fill in before publishing");
    }
    if (form.getPublicToken() == null) form.setPublicToken(newToken());
    form.setStatus("published");
    form.setPublishedAt(Instant.now());
    forms.save(form);
    audit.record(userId, ActorType.USER, null, "form.published", form.getName(),
        Json.obj().put("formId", formId.toString()));
    return toDto(form);
  }

  @Override
  @Transactional
  public FormDto unpublish(UUID userId, UUID formId) {
    FormEntity form = owned(userId, formId);
    form.setStatus("draft");
    forms.save(form);
    audit.record(userId, ActorType.USER, null, "form.unpublished", form.getName(),
        Json.obj().put("formId", formId.toString()));
    return toDto(form);
  }

  @Override
  @Transactional
  public FormDto setWorkflows(UUID userId, UUID formId, List<UUID> workflowIds) {
    FormEntity form = owned(userId, formId);
    Set<UUID> wanted = new LinkedHashSet<>(workflowIds == null ? List.of() : workflowIds);
    for (UUID id : wanted) ownedWorkflow(userId, id);
    for (WorkflowEntity w : workflows.findByFormId(formId)) {
      if (!wanted.contains(w.getId())) {
        w.setFormId(null);
        workflows.save(w);
      }
    }
    for (UUID id : wanted) {
      WorkflowEntity w = ownedWorkflow(userId, id);
      if (!formId.equals(w.getFormId())) {
        w.setFormId(formId);
        workflows.save(w);
      }
    }
    return toDto(form);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Submission> submissions(UUID userId, UUID formId, int limit) {
    owned(userId, formId);
    return submissions.findByFormIdOrderBySubmittedAtDesc(formId,
            PageRequest.of(0, Math.max(1, Math.min(limit, 500)))).stream()
        .map(s -> new Submission(s.getId(), s.getNumber(), withoutFiles(s.getData()), s.getRuns(),
            s.getSubmittedAt()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public FileContent file(UUID userId, UUID formId, UUID submissionId, String field) {
    owned(userId, formId);
    FormSubmissionEntity s = submissions.findById(submissionId)
        .filter(x -> x.getFormId().equals(formId))
        .orElseThrow(() -> new NotFoundException("Submission", submissionId));
    JsonNode value = s.getData().path(field);
    String dataUrl = value.path("dataUrl").asText("");
    int comma = dataUrl.indexOf(',');
    if (comma < 0) throw new NotFoundException("File", submissionId);
    byte[] bytes = Base64.getMimeDecoder().decode(dataUrl.substring(comma + 1));
    return new FileContent(value.path("name").asText(field), value.path("type").asText(), bytes);
  }

  // ── The public form ──────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public PublicForm publicForm(String token) {
    FormEntity form = published(token);
    return new PublicForm(form.getName(), form.getDescription(),
        FormFields.publicFields(form.getFields(), lookupOptions(form)), publicSettings(form));
  }

  @Override
  @Transactional
  public SubmitResult submit(String token, JsonNode data, String client) {
    FormEntity found = published(token);
    throttle(found.getId(), client);
    ObjectNode answers = FormFields.checkSubmission(found.getFields(), data, lookupOptions(found));

    FormEntity form = forms.lockById(found.getId()).orElseThrow();
    int number = form.getSubmissionCount() + 1;
    form.setSubmissionCount(number);
    forms.save(form);
    computeFields(form, answers, number);

    FormSubmissionEntity submission = new FormSubmissionEntity();
    submission.setFormId(form.getId());
    submission.setUserId(form.getUserId());
    submission.setNumber(number);
    submission.setData(answers);
    submission.setRuns(Json.mapper().createArrayNode());
    submissions.save(submission);

    JobQueue q = queue.getIfAvailable();
    if (q != null) {
      q.enqueue(form.getUserId(), JOB_TYPE, Json.obj().put("submissionId", submission.getId().toString()));
    }
    String thanks = form.getSettings().path("successMessage").asText("");
    return new SubmitResult(number, thanks.isBlank() ? "Thank you. Your response was sent." : thanks);
  }

  /** Starts every workflow of the submission's form that is on. Returns how many started. */
  public int startWorkflows(UUID submissionId) {
    FormSubmissionEntity submission = submissions.findById(submissionId).orElse(null);
    if (submission == null) return 0;
    FormEntity form = forms.findById(submission.getFormId()).orElse(null);
    WorkflowEngine runner = engine.getIfAvailable();
    if (form == null || runner == null) return 0;
    ObjectNode input = withoutFiles(submission.getData()).deepCopy();
    input.putObject("_form").put("id", form.getId().toString()).put("name", form.getName())
        .put("submissionId", submissionId.toString()).put("number", submission.getNumber())
        .put("submittedAt", String.valueOf(submission.getSubmittedAt()));
    ArrayNode runs = Json.mapper().createArrayNode();
    int started = 0;
    for (WorkflowEntity w : workflows.findByFormId(form.getId())) {
      if (!w.getUserId().equals(form.getUserId())) continue;
      ObjectNode entry = runs.addObject().put("workflowId", w.getId().toString())
          .put("workflowName", w.getName());
      if (!w.isEnabled()) {
        entry.put("error", "The workflow is off");
        continue;
      }
      try {
        var run = runner.start(w.getId(), w.getVersion(), input, "form:" + form.getId(),
            overridesFor(form.getFields(), w.getId()));
        entry.put("runId", run.id().toString());
        started++;
      } catch (RuntimeException e) {
        log.warn("Form {} could not start workflow {}", form.getId(), w.getId(), e);
        entry.put("error", String.valueOf(e.getMessage()));
      }
    }
    submission.setRuns(runs);
    submissions.save(submission);
    audit.record(form.getUserId(), ActorType.SYSTEM, null, "form.submitted", form.getName(),
        Json.obj().put("formId", form.getId().toString()).put("number", submission.getNumber())
            .put("workflowsStarted", started));
    return started;
  }

  /**
   * What the form's fields bound to {@code workflowId} set in that workflow's steps: each points
   * one setting at the run's input, by the field's link name.
   */
  static ObjectNode overridesFor(JsonNode fields, UUID workflowId) {
    ObjectNode out = Json.obj();
    for (JsonNode f : FormFields.flatten(fields)) {
      JsonNode b = f.path("binding");
      if (FormFields.pending(f) || !b.isObject() || !workflowId.toString().equals(b.path("workflowId").asText())) continue;
      String mode = b.path("mode").asText();
      if ("input".equals(mode)) continue;
      String name = f.path("name").asText();
      String value = "expression".equals(mode) ? "input." + name : "{{ input." + name + " }}";
      ArrayNode list = out.has(b.path("nodeId").asText())
          ? (ArrayNode) out.get(b.path("nodeId").asText()) : out.putArray(b.path("nodeId").asText());
      list.addObject().put("value", value).set("path", b.path("path").deepCopy());
    }
    return out;
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private void apply(UUID userId, FormEntity form, FormDraft draft) {
    String name = draft.name() == null ? "" : draft.name().strip();
    if (name.isEmpty()) throw new ValidationException("Give the form a name");
    if (name.length() > 120) throw new ValidationException("A form's name can be at most 120 characters");
    form.setName(name);
    String description = draft.description();
    if (description != null && description.length() > 5000) {
      throw new ValidationException("A form's description can be at most 5000 characters");
    }
    form.setDescription(description == null || description.isBlank() ? null : description);
    JsonNode fields = FormFields.checkDefinition(draft.fields());
    for (JsonNode f : FormFields.flatten(fields)) {
      String wf = f.path("binding").path("workflowId").asText("");
      if (!wf.isBlank()) ownedWorkflow(userId, uuid(wf));
      String lookupForm = f.path("lookup").path("formId").asText("");
      if (!lookupForm.isBlank()) owned(userId, uuid(lookupForm));
    }
    form.setFields(fields);
    JsonNode settings = draft.settings() != null && draft.settings().isObject() ? draft.settings() : Json.obj();
    if (Json.write(settings).length() > 16_384) throw new ValidationException("The form's settings are too large");
    form.setSettings(settings);
  }

  private void computeFields(FormEntity form, ObjectNode answers, int number) {
    for (JsonNode f : FormFields.flatten(form.getFields())) {
      if (FormFields.pending(f)) continue;
      String type = f.path("type").asText();
      String name = f.path("name").asText();
      if ("auto_number".equals(type)) {
        answers.put(name, f.path("startAt").asInt(1) - 1 + number);
      }
    }
    // Formulas read the answers (and auto numbers) as input.<link name>, as in a workflow.
    for (JsonNode f : FormFields.flatten(form.getFields())) {
      if (FormFields.pending(f) || !"formula".equals(f.path("type").asText())) continue;
      String formula = f.path("formula").asText("");
      if (formula.isBlank()) continue;
      try {
        Map<String, Object> variables = new HashMap<>();
        variables.put("input", Json.mapper().convertValue(withoutFiles(answers), Object.class));
        JsonNode value = sandbox.evaluate(formula, variables);
        if (value != null && !value.isMissingNode()) answers.set(f.path("name").asText(), value);
      } catch (RuntimeException e) {
        log.debug("Formula field {} of form {} failed: {}", f.path("name").asText(), form.getId(), e.getMessage());
      }
    }
  }

  /** The answers with each file reduced to its name, type and size. */
  static ObjectNode withoutFiles(JsonNode data) {
    ObjectNode out = data != null && data.isObject() ? ((ObjectNode) data).deepCopy() : Json.obj();
    out.properties().forEach(e -> {
      if (e.getValue().isObject() && e.getValue().has("dataUrl")) {
        ((ObjectNode) e.getValue()).remove("dataUrl");
        ((ObjectNode) e.getValue()).put("file", true);
      }
    });
    return out;
  }

  private Map<String, List<String>> lookupOptions(FormEntity form) {
    Map<String, List<String>> out = new HashMap<>();
    for (JsonNode f : FormFields.flatten(form.getFields())) {
      if (!"lookup".equals(f.path("type").asText())) continue;
      String other = f.path("lookup").path("formId").asText("");
      String field = f.path("lookup").path("field").asText("");
      if (other.isBlank() || field.isBlank()) continue;
      FormEntity source = forms.findById(uuid(other))
          .filter(x -> x.getUserId().equals(form.getUserId())).orElse(null);
      if (source == null) continue;
      Set<String> values = new LinkedHashSet<>();
      for (FormSubmissionEntity s : submissions.findByFormIdOrderBySubmittedAtDesc(source.getId(), PageRequest.of(0, 500))) {
        JsonNode v = s.getData().path(field);
        if (v.isValueNode() && !v.asText().isBlank()) values.add(v.asText());
        if (values.size() >= 200) break;
      }
      out.put(f.path("id").asText(), new ArrayList<>(values));
    }
    return out;
  }

  private static JsonNode publicSettings(FormEntity form) {
    ObjectNode out = Json.obj();
    JsonNode s = form.getSettings();
    for (String k : List.of("submitLabel", "successMessage", "theme")) {
      if (s.hasNonNull(k)) out.set(k, s.get(k));
    }
    return out;
  }

  private void throttle(UUID formId, String client) {
    long now = System.currentTimeMillis();
    if (!allow("form:" + formId, PER_FORM_PER_MINUTE, now)
        || (client != null && !allow("client:" + formId + ":" + client, PER_CLIENT_PER_MINUTE, now))) {
      throw new RateLimitedException("Too many responses were sent just now. Wait a minute and try again.");
    }
    if (recent.size() > 10_000) recent.clear();
  }

  private boolean allow(String key, int max, long now) {
    Deque<Long> times = recent.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (times) {
      while (!times.isEmpty() && now - times.peekFirst() > 60_000) times.pollFirst();
      if (times.size() >= max) return false;
      times.addLast(now);
      return true;
    }
  }

  private FormEntity published(String token) {
    if (token == null || token.length() < 16 || token.length() > 48) throw new NotFoundException("Form", token);
    return forms.findByPublicToken(token).filter(f -> "published".equals(f.getStatus()))
        .orElseThrow(() -> new NotFoundException("Form", token));
  }

  private FormEntity owned(UUID userId, UUID formId) {
    return forms.findById(formId).filter(f -> f.getUserId().equals(userId))
        .orElseThrow(() -> new NotFoundException("Form", formId));
  }

  private WorkflowEntity ownedWorkflow(UUID userId, UUID workflowId) {
    return workflows.findById(workflowId).filter(w -> w.getUserId().equals(userId))
        .orElseThrow(() -> new NotFoundException("Workflow", workflowId));
  }

  private static UUID uuid(String s) {
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("'" + s + "' is not an id");
    }
  }

  private static String newToken() {
    byte[] bytes = new byte[18];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private FormDto toDto(FormEntity f) {
    List<LinkedWorkflow> linked = workflows.findByFormId(f.getId()).stream()
        .map(w -> new LinkedWorkflow(w.getId(), w.getName(), w.isEnabled())).toList();
    return new FormDto(f.getId(), f.getName(), f.getDescription(), f.getFields(), f.getSettings(),
        f.getStatus(), f.getPublicToken(), f.getPublishedAt(), f.getSourceWorkflowId(),
        f.getSubmissionCount(), f.getCreatedAt(), f.getUpdatedAt(), linked);
  }
}

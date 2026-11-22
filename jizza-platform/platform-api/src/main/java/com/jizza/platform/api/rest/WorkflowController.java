package com.jizza.platform.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.dto.SkillDto;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.FormulaPreview;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.api.workflow.library.ReuseProfile;
import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.engine.security.session.CurrentWorkspace;
import com.jizza.platform.usecase.connector.ConnectorServiceUseCase;
import com.jizza.platform.usecase.skill.SkillUseCase;
import com.jizza.platform.usecase.workflow.WorkflowUseCase;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.api.rest.dto.Responses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The workflow builder surface: save, version, save-as-skill, run and resume (R31-R33). */
@RestController
@AuthenticatedOnly
@RequestMapping("/api")
public class WorkflowController {

  private final WorkflowEngine engine;
  private final WorkflowUseCase workflows;
  private final SkillUseCase skills;
  private final com.jizza.engine.api.orchestrator.AgentDirectory agents;
  private final FormulaPreview formulas;
  private final com.jizza.engine.api.form.Forms forms;
  private final ConnectorServiceUseCase serviceTriggers;

  /** Turns a workflow on or off. */
  public record SetEnabled(@jakarta.validation.constraints.NotNull Boolean enabled) {}

  /** The form whose submissions start a workflow; null for none. */
  public record SetFormTrigger(UUID formId) {}

  public WorkflowController(WorkflowEngine engine, WorkflowUseCase workflows, SkillUseCase skills,
                            com.jizza.engine.api.orchestrator.AgentDirectory agents,
                            FormulaPreview formulas, com.jizza.engine.api.form.Forms forms,
                            ConnectorServiceUseCase serviceTriggers) {
    this.engine = engine;
    this.workflows = workflows;
    this.skills = skills;
    this.agents = agents;
    this.formulas = formulas;
    this.forms = forms;
    this.serviceTriggers = serviceTriggers;
  }

  /**
   * Turns the workflow on or off for its triggers. On holds for its current steps; saving new
   * steps in the builder keeps it on.
   */
  @PutMapping("/workflows/{id}/enabled")
  public WorkflowDto setEnabled(@PathVariable UUID id, @Valid @RequestBody SetEnabled request) {
    return workflows.setEnabled(CurrentUser.userId(), id, request.enabled());
  }

  /** Deletes the workflow, cancelling its unfinished runs; its run history stays. */
  @DeleteMapping("/workflows/{id}")
  @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    workflows.delete(CurrentUser.userId(), id);
  }

  /** Starts the workflow when the form is submitted (only while the workflow is on). */
  @PutMapping("/workflows/{id}/form-trigger")
  public WorkflowDto setFormTrigger(@PathVariable UUID id, @RequestBody SetFormTrigger request) {
    UUID userId = CurrentUser.userId();
    if (request.formId() != null) forms.get(userId, request.formId());
    return workflows.setFormTrigger(userId, id, request.formId());
  }

  /** Starts the workflow whenever the service trigger fires (only while the workflow is on). */
  @PutMapping("/workflows/{id}/service-trigger")
  public WorkflowDto setServiceTrigger(@PathVariable UUID id, @RequestBody SetServiceTrigger request) {
    if (request.triggerId() != null && serviceTriggers.triggerChoices(CurrentWorkspace.access())
        .stream().noneMatch(t -> t.triggerId().equals(request.triggerId()))) {
      throw new com.jizza.engine.api.error.NotFoundException("Trigger", request.triggerId());
    }
    return workflows.setServiceTrigger(CurrentUser.userId(), id, request.triggerId());
  }

  public record SetServiceTrigger(UUID triggerId) {}

  /** Every trigger of the person's services, for the workflow's "Starts when" choice. */
  @GetMapping("/service-triggers")
  public java.util.List<com.jizza.platform.usecase.connector.ConnectorServiceUseCase.TriggerChoice> serviceTriggers() {
    return serviceTriggers.triggerChoices(CurrentWorkspace.access());
  }

  /**
   * What a formula gives against sample data, for the builder's live preview. It runs in the same
   * sandbox as a workflow step, over only the data sent with it.
   */
  @PostMapping("/formula/preview")
  public FormulaPreview.Result previewFormula(@Valid @RequestBody Requests.PreviewFormula request) {
    return formulas.preview(request.formula(), Boolean.TRUE.equals(request.template()), request.data());
  }

  /** What a Script step gives with sample data, for the builder's Test button. Same sandbox as a run. */
  @PostMapping("/formula/script-preview")
  public FormulaPreview.Result previewScript(@Valid @RequestBody Requests.PreviewScript request) {
    return formulas.previewScript(CurrentUser.userId(), request.step(), request.data());
  }

  @GetMapping("/workflows")
  public List<WorkflowDto> list() {
    return workflows.list(CurrentUser.userId());
  }

  @GetMapping("/workflows/{id}")
  public WorkflowDto get(@PathVariable UUID id) {
    return owned(id);
  }

  @PostMapping("/workflows")
  public WorkflowDto save(@Valid @RequestBody Requests.WorkflowSave request) {
    UUID userId = CurrentUser.userId();
    if (request.owningAgentId() != null) {
      agents.byId(request.owningAgentId()).filter(a -> userId.equals(a.userId()))
          .orElseThrow(() -> new com.jizza.engine.api.error.NotFoundException("Agent",
              request.owningAgentId()));
    }
    return workflows.save(userId, request.id(), request.name(),
        request.description(), request.graph(), request.inputSchema(), request.triggerConfig(),
        request.owningAgentId(), ReuseProfile.from(request.reuse()), request.baseVersion());
  }

  /** The workflows Jizza may reuse, with what it keeps about each and their guardrails. */
  @GetMapping("/workflows/library")
  public List<WorkflowLibrary.Match> library() {
    return workflows.library(CurrentUser.userId());
  }

  /** Every workflow the user can run from a chat ({@code /run}), newest first, with its inputs. */
  @GetMapping("/workflows/runnable")
  public List<WorkflowLibrary.Match> runnable() {
    return workflows.runnable(CurrentUser.userId());
  }

  /** Sets when Jizza may reuse a workflow; allowing scheduled runs holds for its current steps. */
  @PutMapping("/workflows/{id}/reuse")
  public WorkflowLibrary.Match describe(@PathVariable UUID id, @RequestBody JsonNode reuse) {
    ReuseProfile profile = ReuseProfile.from(reuse);
    if (profile == null) throw new ValidationException("Send what to keep, or DELETE to remove it");
    return workflows.describe(CurrentUser.userId(), id, profile);
  }

  /** Jizza suggests what the workflow is for and how the user would ask for it, and keeps that. */
  @PostMapping("/workflows/{id}/reuse/suggest")
  public WorkflowLibrary.Match suggestUses(@PathVariable UUID id) {
    return workflows.suggestUses(CurrentUser.userId(), id);
  }

  @DeleteMapping("/workflows/{id}/reuse")
  public WorkflowLibrary.Match forget(@PathVariable UUID id) {
    return workflows.describe(CurrentUser.userId(), id, null);
  }

  @GetMapping("/workflows/{id}/versions")
  public List<WorkflowDto> versions(@PathVariable UUID id) {
    return workflows.versions(CurrentUser.userId(), id);
  }

  @PostMapping("/workflows/{id}/save-skill")
  public SkillDto saveAsSkill(@PathVariable UUID id,
                              @Valid @RequestBody Requests.SaveAsSkill request) {
    return skills.createFromWorkflow(CurrentUser.userId(), id, request.name(),
        request.description(), request.parameterSchema());
  }

  @GetMapping("/skills")
  public List<SkillDto> skills() {
    return skills.list(CurrentUser.userId());
  }

  /**
   * A manual run is how the builder is tested, so an unapproved draft may run this way; anything
   * unattended still requires approval, which the engine enforces.
   */
  @PostMapping("/workflows/{id}/run")
  public RunDto run(@PathVariable UUID id, @RequestBody(required = false) Requests.RunWorkflow request) {
    WorkflowDto workflow = owned(id);
    int version = request == null || request.version() == null
        ? workflow.version() : request.version();
    var input = request == null || request.input() == null ? Json.obj() : request.input();
    return engine.start(id, version, input, "manual:" + CurrentUser.userId());
  }

  @GetMapping("/runs/{id}")
  public RunDto run(@PathVariable UUID id) {
    return ownedRun(id);
  }

  /**
   * Resumes a paused run directly. The usual route is answering the notification, but the builder
   * exposes this so a run can be continued from the run view.
   */
  @PostMapping("/runs/{id}/resume")
  @com.jizza.engine.api.util.StepUpRequired
  public RunDto resume(@PathVariable UUID id, @jakarta.validation.Valid @RequestBody Requests.ResumeRun request) {
    ownedRun(id);
    var payload = Json.obj();
    if (request.instruction() != null) payload.put("instruction", request.instruction());
    if (request.approved() != null) payload.put("approved", request.approved());
    return engine.resume(id, payload);
  }

  @PostMapping("/runs/{id}/cancel")
  public Responses.Message cancel(@PathVariable UUID id) {
    ownedRun(id);
    engine.cancel(id);
    return new Responses.Message("Run cancelled.");
  }

  private WorkflowDto owned(UUID workflowId) {
    return workflows.get(CurrentUser.userId(), workflowId);
  }

  private RunDto ownedRun(UUID runId) {
    RunDto run = engine.get(runId);
    if (!run.userId().equals(CurrentUser.userId())) {
      throw new NotFoundException("Workflow run", runId);
    }
    return run;
  }
}

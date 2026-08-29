package com.jizza.platform.api.rest;

import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.dto.SkillDto;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.api.workflow.WorkflowRegistry;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.skill.SkillUseCase;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.api.rest.dto.Responses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The workflow builder surface: save, version, save-as-skill, run and resume (R31-R33). */
@RestController
@RequestMapping("/api")
public class WorkflowController {

  private final WorkflowRegistry registry;
  private final WorkflowEngine engine;
  private final SkillUseCase skills;

  public WorkflowController(WorkflowRegistry registry, WorkflowEngine engine, SkillUseCase skills) {
    this.registry = registry;
    this.engine = engine;
    this.skills = skills;
  }

  @GetMapping("/workflows")
  public List<WorkflowDto> list() {
    return registry.list(CurrentUser.userId());
  }

  @GetMapping("/workflows/{id}")
  public WorkflowDto get(@PathVariable UUID id) {
    return owned(id);
  }

  @PostMapping("/workflows")
  public WorkflowDto save(@Valid @RequestBody Requests.WorkflowSave request) {
    if (request.id() != null) owned(request.id());
    return registry.save(new WorkflowDto(request.id(), CurrentUser.userId(), request.name(),
        request.description(), request.graph(), request.inputSchema(), request.triggerConfig(),
        com.jizza.engine.api.enums.WorkflowOrigin.USER_BUILT, null, request.owningAgentId(), 1, null,
        null));
  }

  @GetMapping("/workflows/{id}/versions")
  public List<WorkflowDto> versions(@PathVariable UUID id) {
    owned(id);
    return registry.versions(id);
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
  public RunDto resume(@PathVariable UUID id, @RequestBody Requests.ResumeRun request) {
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
    return registry.find(workflowId)
        .filter(workflow -> workflow.userId().equals(CurrentUser.userId()))
        .orElseThrow(() -> new NotFoundException("Workflow", workflowId));
  }

  private RunDto ownedRun(UUID runId) {
    RunDto run = engine.get(runId);
    if (!run.userId().equals(CurrentUser.userId())) {
      throw new NotFoundException("Workflow run", runId);
    }
    return run;
  }
}

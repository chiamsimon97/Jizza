package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.procedure.ProcedureService;
import com.jizza.engine.api.workflow.WorkflowRegistry;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.procedure.ProcedureExampleUseCase;
import com.jizza.platform.usecase.procedure.ProcedureReviewUseCase;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learned procedures: review, version history, diff, rollback and disable (R34-R36).
 *
 * <p>Approving is what makes an agent-authored graph runnable and binds it to a recurring job, so
 * the routes that change that standing all require a fresh re-authentication.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/procedures")
public class ProcedureController {

  private final ProcedureService procedures;
  private final WorkflowRegistry registry;
  private final ProcedureReviewUseCase review;
  private final ProcedureExampleUseCase example;

  public ProcedureController(ProcedureService procedures, WorkflowRegistry registry,
                             ProcedureReviewUseCase review, ProcedureExampleUseCase example) {
    this.procedures = procedures;
    this.registry = registry;
    this.review = review;
    this.example = example;
  }

  /**
   * Adds a sample repeating task and the procedure its agent learned from it, waiting for review,
   * so the user can see how the two connect. Nothing in it reaches outside Jizza.
   */
  @PostMapping("/example")
  public ProcedureExampleUseCase.Example addExample() {
    return example.create(CurrentUser.userId());
  }

  @GetMapping
  public List<ProcedureService.ProcedureSummary> list() {
    return procedures.list(CurrentUser.userId());
  }

  @GetMapping("/{id}/history")
  public List<WorkflowDto> history(@PathVariable UUID id) {
    owned(id);
    return procedures.history(id);
  }

  @GetMapping("/{id}/diff")
  public ProcedureService.ProcedureDiff diff(@PathVariable UUID id, @RequestParam int from,
                                             @RequestParam int to) {
    owned(id);
    return procedures.diff(id, from, to);
  }

  @PostMapping("/{id}/approve")
  @com.jizza.engine.api.util.StepUpRequired
  public Responses.Message approve(@PathVariable UUID id) {
    owned(id);
    procedures.approve(id);
    return new Responses.Message("Approved. The job it came from now follows it exactly.");
  }

  @PostMapping("/{id}/reject")
  @com.jizza.engine.api.util.StepUpRequired
  public Responses.Message reject(@PathVariable UUID id,
                                  @RequestBody(required = false) Requests.RejectProcedure request) {
    owned(id);
    procedures.reject(id, request == null ? null : request.feedback());
    return new Responses.Message("Rejected. It stays out of use and the agent gets your feedback.");
  }

  @PostMapping("/{id}/revise")
  public WorkflowDto revise(@PathVariable UUID id,
                            @Valid @RequestBody Requests.ReviseProcedure request) {
    owned(id);
    return procedures.revise(id, request.graph());
  }

  @PostMapping("/{id}/rollback")
  @com.jizza.engine.api.util.StepUpRequired
  public Responses.Message rollback(@PathVariable UUID id, @RequestParam int toVersion) {
    review.rollback(CurrentUser.userId(), id, toVersion);
    return new Responses.Message("Rolled back to version " + toVersion + ".");
  }

  @PostMapping("/{id}/disable")
  @com.jizza.engine.api.util.StepUpRequired
  public Responses.Message disable(@PathVariable UUID id) {
    review.disable(CurrentUser.userId(), id);
    return new Responses.Message("Disabled and unbound from its job.");
  }

  private void owned(UUID workflowId) {
    registry.find(workflowId)
        .filter(workflow -> workflow.userId().equals(CurrentUser.userId()))
        .orElseThrow(() -> new NotFoundException("Procedure", workflowId));
  }
}

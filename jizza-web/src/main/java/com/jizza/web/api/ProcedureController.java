package com.jizza.web.api;

import com.jizza.common.dto.WorkflowDto;
import com.jizza.common.error.NotFoundException;
import com.jizza.common.procedure.ProcedureService;
import com.jizza.common.workflow.WorkflowRegistry;
import com.jizza.security.session.CurrentUser;
import com.jizza.web.api.dto.Requests;
import com.jizza.web.api.dto.Responses;
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
@RequestMapping("/api/procedures")
public class ProcedureController {

  private final ProcedureService procedures;
  private final WorkflowRegistry registry;

  public ProcedureController(ProcedureService procedures, WorkflowRegistry registry) {
    this.procedures = procedures;
    this.registry = registry;
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
  @com.jizza.common.util.StepUpRequired
  public Responses.Message approve(@PathVariable UUID id) {
    owned(id);
    procedures.approve(id);
    return new Responses.Message("Approved. The job it came from now follows it exactly.");
  }

  @PostMapping("/{id}/reject")
  @com.jizza.common.util.StepUpRequired
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
  @com.jizza.common.util.StepUpRequired
  public Responses.Message rollback(@PathVariable UUID id, @RequestParam int toVersion) {
    owned(id);
    procedures.rollback(id, toVersion);
    return new Responses.Message("Rolled back to version " + toVersion + ".");
  }

  @PostMapping("/{id}/disable")
  @com.jizza.common.util.StepUpRequired
  public Responses.Message disable(@PathVariable UUID id) {
    owned(id);
    procedures.disable(id);
    return new Responses.Message("Disabled and unbound from its job.");
  }

  private void owned(UUID workflowId) {
    registry.find(workflowId)
        .filter(workflow -> workflow.userId().equals(CurrentUser.userId()))
        .orElseThrow(() -> new NotFoundException("Procedure", workflowId));
  }
}

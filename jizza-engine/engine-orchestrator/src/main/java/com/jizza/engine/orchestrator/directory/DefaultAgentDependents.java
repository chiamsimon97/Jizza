package com.jizza.engine.orchestrator.directory;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.TaskStatus;
import com.jizza.engine.api.orchestrator.AgentDependents;
import com.jizza.engine.persistence.entity.ScheduledTaskEntity;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.entity.WorkflowVersionEntity;
import com.jizza.engine.persistence.repository.ScheduledTaskRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.persistence.repository.WorkflowVersionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Finds and moves what points at an agent, across every version of every workflow. */
@Service
public class DefaultAgentDependents implements AgentDependents {

  /** Tasks that are finished keep their history; only these are moved to another agent. */
  private static final Set<TaskStatus> OPEN =
      Set.of(TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.WAITING_APPROVAL);

  private final WorkflowRepository workflows;
  private final WorkflowVersionRepository versions;
  private final ScheduledTaskRepository tasks;

  public DefaultAgentDependents(WorkflowRepository workflows, WorkflowVersionRepository versions,
                                ScheduledTaskRepository tasks) {
    this.workflows = workflows;
    this.versions = versions;
    this.tasks = tasks;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Dependent> of(UUID userId, UUID agentId) {
    List<Dependent> out = new ArrayList<>();
    for (WorkflowEntity workflow : workflows.findByUserIdOrderByCreatedAtDesc(userId)) {
      boolean owned = agentId.equals(workflow.getOwningAgentId());
      boolean step = versions.findByWorkflowIdOrderByVersionAsc(workflow.getId()).stream()
          .map(WorkflowVersionEntity::getGraph)
          .anyMatch(graph -> namesAgent(graph, agentId));
      if (owned || step) {
        out.add(new Dependent("workflow", workflow.getId(), workflow.getName(),
            workflow.getStatus() == null ? null : workflow.getStatus().name().toLowerCase(Locale.ROOT),
            owned));
      }
    }
    for (ScheduledTaskEntity task : tasks.findByUserIdOrderByNextRunAtAsc(userId)) {
      if (agentId.equals(task.getAgentId())) {
        out.add(new Dependent("task", task.getId(), task.getTitle(),
            task.getStatus() == null ? null : task.getStatus().name().toLowerCase(Locale.ROOT),
            true));
      }
    }
    return out;
  }

  @Override
  @Transactional
  public int reassign(UUID userId, UUID fromAgentId, UUID toAgentId) {
    int moved = 0;
    for (WorkflowEntity workflow : workflows.findByUserIdOrderByCreatedAtDesc(userId)) {
      if (fromAgentId.equals(workflow.getOwningAgentId())) {
        workflow.setOwningAgentId(toAgentId);
        workflows.save(workflow);
        moved++;
      }
    }
    for (ScheduledTaskEntity task : tasks.findByUserIdOrderByNextRunAtAsc(userId)) {
      if (fromAgentId.equals(task.getAgentId()) && OPEN.contains(task.getStatus())) {
        task.setAgentId(toAgentId);
        tasks.save(task);
        moved++;
      }
    }
    return moved;
  }

  /** True when any agent step of the graph is assigned to this agent. */
  static boolean namesAgent(JsonNode graph, UUID agentId) {
    if (graph == null) return false;
    String id = agentId.toString();
    for (JsonNode node : graph.path("nodes")) {
      if (id.equals(node.path("config").path("agentId").asText(null))) return true;
    }
    return false;
  }
}

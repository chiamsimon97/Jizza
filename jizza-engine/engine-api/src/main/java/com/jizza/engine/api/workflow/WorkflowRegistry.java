package com.jizza.engine.api.workflow;

import com.jizza.engine.api.dto.WorkflowDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read/write model over workflows and their version snapshots, shared with procedures and the
 * scheduler. Skills live behind {@link SkillStore}. */
public interface WorkflowRegistry {

  Optional<WorkflowDto> find(UUID workflowId);

  Optional<WorkflowDto> findVersion(UUID workflowId, int version);

  List<WorkflowDto> versions(UUID workflowId);

  List<WorkflowDto> list(UUID userId);

  WorkflowDto save(WorkflowDto workflow);
}

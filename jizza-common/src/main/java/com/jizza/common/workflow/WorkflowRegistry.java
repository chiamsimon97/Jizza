package com.jizza.common.workflow;

import com.jizza.common.dto.SkillDto;
import com.jizza.common.dto.WorkflowDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read/write model over workflows, versions and skills, shared with procedures and the scheduler. */
public interface WorkflowRegistry {

  Optional<WorkflowDto> find(UUID workflowId);

  Optional<WorkflowDto> findVersion(UUID workflowId, int version);

  List<WorkflowDto> versions(UUID workflowId);

  List<WorkflowDto> list(UUID userId);

  WorkflowDto save(WorkflowDto workflow);

  SkillDto saveAsSkill(UUID workflowId, String name, String description,
                       com.fasterxml.jackson.databind.JsonNode parameterSchema);

  List<SkillDto> skills(UUID userId);
}

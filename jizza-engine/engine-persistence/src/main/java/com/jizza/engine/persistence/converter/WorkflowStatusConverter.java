package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.WorkflowStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class WorkflowStatusConverter extends LowerCaseEnumConverter<WorkflowStatus> {
  public WorkflowStatusConverter() { super(WorkflowStatus.class); }
}

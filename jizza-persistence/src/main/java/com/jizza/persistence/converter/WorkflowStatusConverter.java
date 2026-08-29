package com.jizza.persistence.converter;

import com.jizza.common.enums.WorkflowStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class WorkflowStatusConverter extends LowerCaseEnumConverter<WorkflowStatus> {
  public WorkflowStatusConverter() { super(WorkflowStatus.class); }
}

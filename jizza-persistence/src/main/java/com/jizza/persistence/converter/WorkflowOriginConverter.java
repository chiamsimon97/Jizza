package com.jizza.persistence.converter;

import com.jizza.common.enums.WorkflowOrigin;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class WorkflowOriginConverter extends LowerCaseEnumConverter<WorkflowOrigin> {
  public WorkflowOriginConverter() { super(WorkflowOrigin.class); }
}

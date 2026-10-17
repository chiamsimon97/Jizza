package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.WorkflowOrigin;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class WorkflowOriginConverter extends LowerCaseEnumConverter<WorkflowOrigin> {
  public WorkflowOriginConverter() { super(WorkflowOrigin.class); }
}

package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.WorkspaceType;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class WorkspaceTypeConverter extends LowerCaseEnumConverter<WorkspaceType> {
  public WorkspaceTypeConverter() { super(WorkspaceType.class); }
}

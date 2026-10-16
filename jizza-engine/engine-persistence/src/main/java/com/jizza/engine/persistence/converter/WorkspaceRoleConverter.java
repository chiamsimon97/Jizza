package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.WorkspaceRole;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class WorkspaceRoleConverter extends LowerCaseEnumConverter<WorkspaceRole> {
  public WorkspaceRoleConverter() { super(WorkspaceRole.class); }
}

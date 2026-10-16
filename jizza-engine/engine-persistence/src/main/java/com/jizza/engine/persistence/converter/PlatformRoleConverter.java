package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.PlatformRole;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class PlatformRoleConverter extends LowerCaseEnumConverter<PlatformRole> {
  public PlatformRoleConverter() { super(PlatformRole.class); }
}

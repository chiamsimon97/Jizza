package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.RunStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RunStatusConverter extends LowerCaseEnumConverter<RunStatus> {
  public RunStatusConverter() { super(RunStatus.class); }
}

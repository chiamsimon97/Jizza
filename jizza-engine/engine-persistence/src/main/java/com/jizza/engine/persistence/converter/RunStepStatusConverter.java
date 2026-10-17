package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.RunStepStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RunStepStatusConverter extends LowerCaseEnumConverter<RunStepStatus> {
  public RunStepStatusConverter() { super(RunStepStatus.class); }
}

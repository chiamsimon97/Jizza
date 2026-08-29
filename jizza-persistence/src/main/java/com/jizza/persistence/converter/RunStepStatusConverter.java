package com.jizza.persistence.converter;

import com.jizza.common.enums.RunStepStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RunStepStatusConverter extends LowerCaseEnumConverter<RunStepStatus> {
  public RunStepStatusConverter() { super(RunStepStatus.class); }
}

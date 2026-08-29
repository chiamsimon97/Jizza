package com.jizza.persistence.converter;

import com.jizza.common.enums.RunStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RunStatusConverter extends LowerCaseEnumConverter<RunStatus> {
  public RunStatusConverter() { super(RunStatus.class); }
}

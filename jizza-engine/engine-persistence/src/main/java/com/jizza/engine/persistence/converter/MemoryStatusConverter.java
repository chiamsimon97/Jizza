package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.MemoryStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class MemoryStatusConverter extends LowerCaseEnumConverter<MemoryStatus> {
  public MemoryStatusConverter() { super(MemoryStatus.class); }
}

package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.MemorySource;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class MemorySourceConverter extends LowerCaseEnumConverter<MemorySource> {
  public MemorySourceConverter() { super(MemorySource.class); }
}

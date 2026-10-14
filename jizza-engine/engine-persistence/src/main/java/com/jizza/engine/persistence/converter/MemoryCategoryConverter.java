package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.MemoryCategory;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class MemoryCategoryConverter extends LowerCaseEnumConverter<MemoryCategory> {
  public MemoryCategoryConverter() { super(MemoryCategory.class); }
}

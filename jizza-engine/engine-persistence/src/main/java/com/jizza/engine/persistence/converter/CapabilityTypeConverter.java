package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.CapabilityType;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CapabilityTypeConverter extends LowerCaseEnumConverter<CapabilityType> {
  public CapabilityTypeConverter() { super(CapabilityType.class); }
}

package com.jizza.persistence.converter;

import com.jizza.common.enums.CapabilityType;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CapabilityTypeConverter extends LowerCaseEnumConverter<CapabilityType> {
  public CapabilityTypeConverter() { super(CapabilityType.class); }
}

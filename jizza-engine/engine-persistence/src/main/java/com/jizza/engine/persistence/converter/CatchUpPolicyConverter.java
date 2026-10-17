package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.CatchUpPolicy;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CatchUpPolicyConverter extends LowerCaseEnumConverter<CatchUpPolicy> {
  public CatchUpPolicyConverter() { super(CatchUpPolicy.class); }
}

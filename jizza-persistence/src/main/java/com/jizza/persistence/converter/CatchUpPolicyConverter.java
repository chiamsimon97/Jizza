package com.jizza.persistence.converter;

import com.jizza.common.enums.CatchUpPolicy;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CatchUpPolicyConverter extends LowerCaseEnumConverter<CatchUpPolicy> {
  public CatchUpPolicyConverter() { super(CatchUpPolicy.class); }
}

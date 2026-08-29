package com.jizza.persistence.converter;

import com.jizza.common.enums.EscalationState;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class EscalationStateConverter extends LowerCaseEnumConverter<EscalationState> {
  public EscalationStateConverter() { super(EscalationState.class); }
}

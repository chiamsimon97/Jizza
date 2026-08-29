package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.EscalationState;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class EscalationStateConverter extends LowerCaseEnumConverter<EscalationState> {
  public EscalationStateConverter() { super(EscalationState.class); }
}

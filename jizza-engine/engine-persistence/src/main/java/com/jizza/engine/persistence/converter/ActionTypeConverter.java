package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.ActionType;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ActionTypeConverter extends LowerCaseEnumConverter<ActionType> {
  public ActionTypeConverter() { super(ActionType.class); }
}

package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.ActorType;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ActorTypeConverter extends LowerCaseEnumConverter<ActorType> {
  public ActorTypeConverter() { super(ActorType.class); }
}

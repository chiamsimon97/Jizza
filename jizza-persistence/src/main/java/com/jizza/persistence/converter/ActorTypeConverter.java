package com.jizza.persistence.converter;

import com.jizza.common.enums.ActorType;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ActorTypeConverter extends LowerCaseEnumConverter<ActorType> {
  public ActorTypeConverter() { super(ActorType.class); }
}

package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.IdempotencyStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class IdempotencyStatusConverter extends LowerCaseEnumConverter<IdempotencyStatus> {
  public IdempotencyStatusConverter() { super(IdempotencyStatus.class); }
}

package com.jizza.persistence.converter;

import com.jizza.common.enums.IdempotencyStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class IdempotencyStatusConverter extends LowerCaseEnumConverter<IdempotencyStatus> {
  public IdempotencyStatusConverter() { super(IdempotencyStatus.class); }
}

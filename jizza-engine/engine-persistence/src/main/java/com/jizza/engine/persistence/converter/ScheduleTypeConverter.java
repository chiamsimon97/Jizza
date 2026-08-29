package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.ScheduleType;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ScheduleTypeConverter extends LowerCaseEnumConverter<ScheduleType> {
  public ScheduleTypeConverter() { super(ScheduleType.class); }
}

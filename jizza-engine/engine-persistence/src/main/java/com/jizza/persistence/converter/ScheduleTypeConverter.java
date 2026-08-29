package com.jizza.persistence.converter;

import com.jizza.common.enums.ScheduleType;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ScheduleTypeConverter extends LowerCaseEnumConverter<ScheduleType> {
  public ScheduleTypeConverter() { super(ScheduleType.class); }
}

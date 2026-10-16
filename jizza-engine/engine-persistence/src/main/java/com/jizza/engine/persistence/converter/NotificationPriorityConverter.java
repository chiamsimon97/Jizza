package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.NotificationPriority;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class NotificationPriorityConverter extends LowerCaseEnumConverter<NotificationPriority> {
  public NotificationPriorityConverter() { super(NotificationPriority.class); }
}

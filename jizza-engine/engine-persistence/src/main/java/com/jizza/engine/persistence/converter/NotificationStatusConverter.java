package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.NotificationStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class NotificationStatusConverter extends LowerCaseEnumConverter<NotificationStatus> {
  public NotificationStatusConverter() { super(NotificationStatus.class); }
}

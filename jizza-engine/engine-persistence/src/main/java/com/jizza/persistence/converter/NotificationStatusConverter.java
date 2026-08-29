package com.jizza.persistence.converter;

import com.jizza.common.enums.NotificationStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class NotificationStatusConverter extends LowerCaseEnumConverter<NotificationStatus> {
  public NotificationStatusConverter() { super(NotificationStatus.class); }
}

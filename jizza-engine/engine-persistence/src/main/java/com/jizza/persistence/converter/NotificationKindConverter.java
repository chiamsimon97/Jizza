package com.jizza.persistence.converter;

import com.jizza.common.enums.NotificationKind;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class NotificationKindConverter extends LowerCaseEnumConverter<NotificationKind> {
  public NotificationKindConverter() { super(NotificationKind.class); }
}

package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.NotificationKind;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class NotificationKindConverter extends LowerCaseEnumConverter<NotificationKind> {
  public NotificationKindConverter() { super(NotificationKind.class); }
}

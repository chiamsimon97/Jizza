package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.UserStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class UserStatusConverter extends LowerCaseEnumConverter<UserStatus> {
  public UserStatusConverter() { super(UserStatus.class); }
}

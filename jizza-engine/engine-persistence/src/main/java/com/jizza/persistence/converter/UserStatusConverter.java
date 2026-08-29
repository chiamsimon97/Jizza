package com.jizza.persistence.converter;

import com.jizza.common.enums.UserStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class UserStatusConverter extends LowerCaseEnumConverter<UserStatus> {
  public UserStatusConverter() { super(UserStatus.class); }
}

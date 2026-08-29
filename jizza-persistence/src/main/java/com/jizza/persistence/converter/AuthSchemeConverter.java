package com.jizza.persistence.converter;

import com.jizza.common.enums.AuthScheme;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class AuthSchemeConverter extends LowerCaseEnumConverter<AuthScheme> {
  public AuthSchemeConverter() { super(AuthScheme.class); }
}

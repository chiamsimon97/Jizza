package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.AuthScheme;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class AuthSchemeConverter extends LowerCaseEnumConverter<AuthScheme> {
  public AuthSchemeConverter() { super(AuthScheme.class); }
}

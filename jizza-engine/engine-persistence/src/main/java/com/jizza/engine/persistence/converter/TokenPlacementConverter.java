package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.TokenPlacement;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TokenPlacementConverter extends LowerCaseEnumConverter<TokenPlacement> {
  public TokenPlacementConverter() { super(TokenPlacement.class); }
}

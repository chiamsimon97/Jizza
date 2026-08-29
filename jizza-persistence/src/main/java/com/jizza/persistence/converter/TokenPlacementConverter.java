package com.jizza.persistence.converter;

import com.jizza.common.enums.TokenPlacement;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TokenPlacementConverter extends LowerCaseEnumConverter<TokenPlacement> {
  public TokenPlacementConverter() { super(TokenPlacement.class); }
}

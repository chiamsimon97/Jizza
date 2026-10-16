package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.MembershipStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class MembershipStatusConverter extends LowerCaseEnumConverter<MembershipStatus> {
  public MembershipStatusConverter() { super(MembershipStatus.class); }
}

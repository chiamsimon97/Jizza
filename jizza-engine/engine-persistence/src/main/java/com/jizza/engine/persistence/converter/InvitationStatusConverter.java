package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.InvitationStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class InvitationStatusConverter extends LowerCaseEnumConverter<InvitationStatus> {
  public InvitationStatusConverter() { super(InvitationStatus.class); }
}

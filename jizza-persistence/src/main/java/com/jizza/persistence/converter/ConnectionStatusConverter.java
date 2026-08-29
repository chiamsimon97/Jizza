package com.jizza.persistence.converter;

import com.jizza.common.enums.ConnectionStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ConnectionStatusConverter extends LowerCaseEnumConverter<ConnectionStatus> {
  public ConnectionStatusConverter() { super(ConnectionStatus.class); }
}

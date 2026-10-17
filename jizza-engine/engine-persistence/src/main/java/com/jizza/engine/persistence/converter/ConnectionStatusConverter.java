package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.ConnectionStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ConnectionStatusConverter extends LowerCaseEnumConverter<ConnectionStatus> {
  public ConnectionStatusConverter() { super(ConnectionStatus.class); }
}

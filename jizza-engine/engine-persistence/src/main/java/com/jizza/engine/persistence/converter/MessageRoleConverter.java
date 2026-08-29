package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.MessageRole;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class MessageRoleConverter extends LowerCaseEnumConverter<MessageRole> {
  public MessageRoleConverter() { super(MessageRole.class); }
}

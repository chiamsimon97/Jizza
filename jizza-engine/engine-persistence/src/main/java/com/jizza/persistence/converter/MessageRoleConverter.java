package com.jizza.persistence.converter;

import com.jizza.common.enums.MessageRole;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class MessageRoleConverter extends LowerCaseEnumConverter<MessageRole> {
  public MessageRoleConverter() { super(MessageRole.class); }
}

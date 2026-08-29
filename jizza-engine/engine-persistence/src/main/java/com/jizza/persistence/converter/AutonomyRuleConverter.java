package com.jizza.persistence.converter;

import com.jizza.common.enums.AutonomyRule;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class AutonomyRuleConverter extends LowerCaseEnumConverter<AutonomyRule> {
  public AutonomyRuleConverter() { super(AutonomyRule.class); }
}

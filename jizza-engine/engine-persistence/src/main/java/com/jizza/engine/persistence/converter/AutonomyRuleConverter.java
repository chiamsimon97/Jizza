package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.AutonomyRule;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class AutonomyRuleConverter extends LowerCaseEnumConverter<AutonomyRule> {
  public AutonomyRuleConverter() { super(AutonomyRule.class); }
}

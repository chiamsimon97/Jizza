package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.SubjectKind;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class SubjectKindConverter extends LowerCaseEnumConverter<SubjectKind> {
  public SubjectKindConverter() { super(SubjectKind.class); }
}

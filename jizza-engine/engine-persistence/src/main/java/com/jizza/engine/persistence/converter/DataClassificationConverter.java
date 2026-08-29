package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.DataClassification;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class DataClassificationConverter extends LowerCaseEnumConverter<DataClassification> {
  public DataClassificationConverter() { super(DataClassification.class); }
}

package com.jizza.persistence.converter;

import com.jizza.common.enums.DataClassification;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class DataClassificationConverter extends LowerCaseEnumConverter<DataClassification> {
  public DataClassificationConverter() { super(DataClassification.class); }
}

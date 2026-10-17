package com.jizza.engine.persistence.converter;

import jakarta.persistence.AttributeConverter;
import java.util.Locale;

/**
 * The Contract stores enumerated columns as lower-case varchar guarded by a CHECK constraint, so the
 * database keeps exactly the vocabulary the schema declares rather than Java constant spelling.
 */
public abstract class LowerCaseEnumConverter<E extends Enum<E>> implements AttributeConverter<E, String> {

  private final Class<E> type;

  protected LowerCaseEnumConverter(Class<E> type) { this.type = type; }

  @Override
  public String convertToDatabaseColumn(E attribute) {
    return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
  }

  @Override
  public E convertToEntityAttribute(String dbData) {
    return dbData == null ? null : Enum.valueOf(type, dbData.toUpperCase(Locale.ROOT));
  }
}

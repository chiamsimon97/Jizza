package com.jizza.engine.persistence.converter;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Applied with {@code @Convert} to the JSON columns that can carry personal data. The column
 * stays {@code jsonb}; what lands in it is the sealed form (see {@link SealedJson}).
 */
@Converter
public class SealedJsonConverter implements AttributeConverter<JsonNode, JsonNode> {

  @Override
  public JsonNode convertToDatabaseColumn(JsonNode attribute) {
    return SealedJson.seal(attribute);
  }

  @Override
  public JsonNode convertToEntityAttribute(JsonNode dbData) {
    return SealedJson.unseal(dbData);
  }
}

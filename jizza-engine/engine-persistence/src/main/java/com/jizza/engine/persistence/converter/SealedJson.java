package com.jizza.engine.persistence.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.crypto.EncryptionService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Seals JSON columns that can carry personal data (run context, step inputs and outputs, a
 * notification card's values and answers) so a database dump shows ciphertext, the same way
 * {@code device_commands} and {@code web_recording_steps} are protected.
 *
 * <p>The sealed form is itself JSON, {@code {"_sealed": "<base64>"}}, so the column type stays
 * {@code jsonb} and rows written before sealing existed (any other shape) still read as they
 * are. Nothing queries inside these columns in SQL, so opaque content costs nothing.
 *
 * <p>The {@link EncryptionService} is handed in at startup. Until then — and in unit tests
 * that never touch a database — values pass through unchanged, and
 * {@code jizza.security.seal-at-rest=false} turns sealing off while still reading sealed rows.
 */
public final class SealedJson {

  /** The one field of a sealed value. The underscore keeps it out of every input namespace. */
  public static final String FIELD = "_sealed";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static volatile EncryptionService encryption;
  private static volatile boolean sealOnWrite = true;

  private SealedJson() {}

  /** Called once at startup; {@code service} null leaves values readable but unsealed. */
  public static void initialize(EncryptionService service, boolean sealNewWrites) {
    encryption = service;
    sealOnWrite = sealNewWrites;
  }

  public static JsonNode seal(JsonNode plain) {
    EncryptionService service = encryption;
    if (plain == null || plain.isNull() || service == null || !sealOnWrite) return plain;
    // Never double-seal (a value read sealed and written back unchanged).
    if (isSealed(plain)) return plain;
    byte[] bytes = plain.toString().getBytes(StandardCharsets.UTF_8);
    ObjectNode out = MAPPER.createObjectNode();
    out.put(FIELD, Base64.getEncoder().encodeToString(service.encrypt(bytes)));
    return out;
  }

  public static JsonNode unseal(JsonNode stored) {
    if (stored == null || !isSealed(stored)) return stored;
    EncryptionService service = encryption;
    if (service == null) {
      throw new IllegalStateException("A sealed value cannot be read before encryption is set up");
    }
    byte[] sealed = Base64.getDecoder().decode(stored.get(FIELD).asText());
    try {
      return MAPPER.readTree(new String(service.decrypt(sealed), StandardCharsets.UTF_8));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("A sealed value did not hold JSON", e);
    }
  }

  /** An object whose only field is {@link #FIELD}, holding text. */
  public static boolean isSealed(JsonNode node) {
    return node != null && node.isObject() && node.size() == 1
        && node.path(FIELD).isTextual();
  }
}

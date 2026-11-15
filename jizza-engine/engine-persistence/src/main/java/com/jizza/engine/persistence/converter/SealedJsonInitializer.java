package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.crypto.EncryptionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hands the application's {@link EncryptionService} to {@link SealedJson} at startup. In a test
 * slice without one, values pass through unsealed (and reading a sealed row fails loudly rather
 * than leaking ciphertext onward as data).
 */
@Component
public class SealedJsonInitializer {

  public SealedJsonInitializer(
      ObjectProvider<EncryptionService> encryption,
      @Value("${jizza.security.seal-at-rest:true}") boolean sealAtRest) {
    SealedJson.initialize(encryption.getIfAvailable(), sealAtRest);
  }
}

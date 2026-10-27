package com.jizza.engine.devices.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.device.PushProvider;
import com.jizza.engine.persistence.entity.DevicePushTokenEntity;
import com.jizza.engine.persistence.repository.DevicePushTokenRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Wakes a phone through its push service. A push carries only ids ("you have work"); the app
 * fetches the rest over HTTPS after it wakes. With no provider configured for a phone's service,
 * the phone picks the work up the next time it polls, which is logged so it is not a mystery.
 */
@Component
public class PushDispatcher {

  private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

  private final DevicePushTokenRepository tokens;
  private final EncryptionService encryption;
  private final List<PushProvider> providers;

  public PushDispatcher(DevicePushTokenRepository tokens, EncryptionService encryption,
                        List<PushProvider> providers) {
    this.tokens = tokens;
    this.encryption = encryption;
    this.providers = providers;
  }

  /** @return true when a push was handed to the push service */
  public boolean wake(UUID deviceId, JsonNode data) {
    DevicePushTokenEntity token = tokens.findById(deviceId).orElse(null);
    if (token == null) return false;
    PushProvider provider = providers.stream().filter(p -> p.name().equals(token.getProvider()))
        .findFirst().orElse(null);
    if (provider == null) {
      log.info("No {} push provider is configured, so device {} will pick up its work at its "
          + "next poll", token.getProvider(), deviceId);
      return false;
    }
    try {
      boolean valid = provider.send(encryption.decryptString(token.getToken()), data);
      if (!valid) {
        log.info("The push token of device {} is no longer valid; forgetting it", deviceId);
        tokens.delete(token);
      }
      return valid;
    } catch (RuntimeException e) {
      log.warn("Could not push to device {}", deviceId, e);
      return false;
    }
  }
}

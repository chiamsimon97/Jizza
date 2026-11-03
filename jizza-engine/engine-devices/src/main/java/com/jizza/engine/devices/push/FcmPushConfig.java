package com.jizza.engine.devices.push;

import com.jizza.engine.api.device.PushProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Turns on Firebase push when {@code jizza.devices.push.fcm.credentials-file} (the environment
 * variable {@code JIZZA_FCM_CREDENTIALS}) names a service account key file. A missing or unreadable
 * file is logged and push stays off, so a bad path never stops the server starting.
 */
@Configuration
public class FcmPushConfig {

  private static final Logger log = LoggerFactory.getLogger(FcmPushConfig.class);

  @Bean
  public PushProvider fcmPushProvider(
      @Value("${jizza.devices.push.fcm.credentials-file:}") String credentialsFile) {
    if (credentialsFile == null || credentialsFile.isBlank()) {
      log.info("Firebase push is off: set JIZZA_FCM_CREDENTIALS to the service account key file");
      return new Off();
    }
    Path path = Path.of(credentialsFile.trim());
    try {
      if (!Files.isReadable(path)) {
        log.warn("Firebase push is off: cannot read the key file {}", path);
        return new Off();
      }
      FcmPushProvider provider = FcmPushProvider.fromFile(path);
      log.info("Firebase push is on");
      return provider;
    } catch (IOException | RuntimeException e) {
      log.warn("Firebase push is off: the key file {} is not a Firebase service account key ({})",
          path, e.getMessage());
      return new Off();
    }
  }

  /**
   * Stands in when push is not set up. It answers to no provider name, so {@link PushDispatcher}
   * logs that the phone will pick its work up at its next check-in, as before.
   */
  static final class Off implements PushProvider {
    @Override
    public String name() {
      return "none";
    }

    @Override
    public boolean send(String token, com.fasterxml.jackson.databind.JsonNode data) {
      return true;
    }
  }
}

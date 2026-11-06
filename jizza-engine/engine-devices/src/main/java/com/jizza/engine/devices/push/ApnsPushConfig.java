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
 * Turns on Apple push for the iPhone app when the APNs auth key is configured:
 * {@code JIZZA_APNS_KEY_FILE} (the AuthKey_XXXX.p8 file, kept outside the repository),
 * {@code JIZZA_APNS_KEY_ID}, {@code JIZZA_APNS_TEAM_ID} and {@code JIZZA_APNS_TOPIC} (the app's
 * bundle id). {@code JIZZA_APNS_SANDBOX=true} sends to Apple's development service, which is what
 * apps installed from Xcode use. Anything missing or unreadable is logged and push stays off, so a
 * bad setting never stops the server starting.
 */
@Configuration
public class ApnsPushConfig {

  private static final Logger log = LoggerFactory.getLogger(ApnsPushConfig.class);

  @Bean
  public PushProvider apnsPushProvider(
      @Value("${jizza.devices.push.apns.key-file:}") String keyFile,
      @Value("${jizza.devices.push.apns.key-id:}") String keyId,
      @Value("${jizza.devices.push.apns.team-id:}") String teamId,
      @Value("${jizza.devices.push.apns.topic:}") String topic,
      @Value("${jizza.devices.push.apns.sandbox:false}") boolean sandbox) {
    if (blank(keyFile)) {
      log.info("Apple push is off: set JIZZA_APNS_KEY_FILE, JIZZA_APNS_KEY_ID, JIZZA_APNS_TEAM_ID "
          + "and JIZZA_APNS_TOPIC");
      return new Off();
    }
    if (blank(keyId) || blank(teamId) || blank(topic)) {
      log.warn("Apple push is off: JIZZA_APNS_KEY_ID, JIZZA_APNS_TEAM_ID and JIZZA_APNS_TOPIC "
          + "are all needed with the key file");
      return new Off();
    }
    Path path = Path.of(keyFile.trim());
    try {
      if (!Files.isReadable(path)) {
        log.warn("Apple push is off: cannot read the key file {}", path);
        return new Off();
      }
      ApnsPushProvider provider = ApnsPushProvider.create(path, keyId, teamId, topic, sandbox);
      log.info("Apple push is on ({} service, app {})", sandbox ? "development" : "production",
          topic.trim());
      return provider;
    } catch (IOException | RuntimeException e) {
      log.warn("Apple push is off: the key file {} is not an APNs auth key ({})", path,
          e.getMessage());
      return new Off();
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  /** Stands in when Apple push is not set up; answers to no provider name. */
  static final class Off implements PushProvider {
    @Override
    public String name() {
      return "none-apns";
    }

    @Override
    public boolean send(String token, com.fasterxml.jackson.databind.JsonNode data) {
      return true;
    }
  }
}

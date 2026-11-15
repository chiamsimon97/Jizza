package com.jizza.engine.devices.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.devices.config.DeviceProperties;
import com.jizza.engine.devices.gateway.MessageSignatures.Verdict;
import com.jizza.engine.devices.registry.DeviceRegistryService;
import com.jizza.engine.persistence.entity.DeviceEntity;
import com.jizza.engine.persistence.repository.DeviceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessageSignaturesTest {

  private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
  private final ObjectMapper json = new ObjectMapper();
  private final UUID deviceId = UUID.randomUUID();
  private DeviceRegistryService registry;
  private DeviceRepository devices;
  private DeviceProperties props;
  private DeviceEntity device;
  private MessageSignatures signatures;

  @BeforeEach
  void setUp() {
    registry = mock(DeviceRegistryService.class);
    devices = mock(DeviceRepository.class);
    props = new DeviceProperties();
    device = new DeviceEntity();
    when(devices.findById(deviceId)).thenReturn(Optional.of(device));
    signatures = new MessageSignatures(registry, devices, props, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private ObjectNode envelope(String id, Instant ts, String sig) {
    ObjectNode node = json.createObjectNode().put("v", 1).put("id", id).put("type", "result")
        .put("ts", ts.toString());
    if (sig != null) node.put("sig", sig);
    return node;
  }

  @Test
  void anUnsignedMessageFromADeviceThatNeverSignedIsAccepted() {
    assertThat(signatures.check(deviceId, envelope("m1", NOW, null))).isEqualTo(Verdict.ACCEPT);
  }

  @Test
  void onceADeviceSignsItMustSignEveryMessage() {
    when(registry.verifySignature(eq(deviceId), anyString(), eq("p256:good"))).thenReturn(true);
    assertThat(signatures.check(deviceId, envelope("m1", NOW, "p256:good")))
        .isEqualTo(Verdict.ACCEPT);
    assertThat(device.isSignsMessages()).isTrue();
    verify(devices).save(device);
    assertThat(signatures.check(deviceId, envelope("m2", NOW, null)))
        .isEqualTo(Verdict.REFUSE_UNSIGNED);
  }

  @Test
  void aDeviceMarkedSigningInTheDatabaseIsHeldToItAfterARestart() {
    device.setSignsMessages(true);
    assertThat(signatures.check(deviceId, envelope("m1", NOW, null)))
        .isEqualTo(Verdict.REFUSE_UNSIGNED);
  }

  @Test
  void theSettingRefusesUnsignedMessagesFromEveryDevice() {
    props.setRequireSignedMessages(true);
    assertThat(signatures.check(deviceId, envelope("m1", NOW, null)))
        .isEqualTo(Verdict.REFUSE_UNSIGNED);
  }

  @Test
  void aBadSignatureIsRefusedAndDoesNotMarkTheDevice() {
    when(registry.verifySignature(any(), anyString(), anyString())).thenReturn(false);
    assertThat(signatures.check(deviceId, envelope("m1", NOW, "p256:bad")))
        .isEqualTo(Verdict.REFUSE_BAD_SIGNATURE);
    verify(devices, never()).save(any());
  }

  @Test
  void theSignatureCoversTheEnvelopeWithoutSig() {
    when(registry.verifySignature(eq(deviceId), anyString(), anyString())).thenAnswer(call -> {
      String text = call.getArgument(1);
      return !text.contains("sig") && text.contains("\"id\":\"m1\"");
    });
    assertThat(signatures.check(deviceId, envelope("m1", NOW, "p256:x")))
        .isEqualTo(Verdict.ACCEPT);
  }

  @Test
  void theSameSignedMessageTwiceIsAReplay() {
    when(registry.verifySignature(any(), anyString(), anyString())).thenReturn(true);
    assertThat(signatures.check(deviceId, envelope("m1", NOW, "p256:x")))
        .isEqualTo(Verdict.ACCEPT);
    assertThat(signatures.check(deviceId, envelope("m1", NOW, "p256:x")))
        .isEqualTo(Verdict.REPLAY);
  }

  @Test
  void oldOrFutureMessagesAreStale() {
    when(registry.verifySignature(any(), anyString(), anyString())).thenReturn(true);
    assertThat(signatures.check(deviceId,
        envelope("m1", NOW.minus(MessageSignatures.MAX_AGE).minusSeconds(1), "p256:x")))
        .isEqualTo(Verdict.REFUSE_STALE);
    assertThat(signatures.check(deviceId,
        envelope("m2", NOW.plus(MessageSignatures.MAX_SKEW).plusSeconds(1), "p256:x")))
        .isEqualTo(Verdict.REFUSE_STALE);
    assertThat(signatures.check(deviceId,
        envelope("m3", NOW.minusSeconds(3600), "p256:x"))).isEqualTo(Verdict.ACCEPT);
  }
}

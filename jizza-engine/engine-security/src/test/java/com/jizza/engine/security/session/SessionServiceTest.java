package com.jizza.engine.security.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.jizza.engine.persistence.entity.SessionEntity;
import com.jizza.engine.persistence.repository.SessionRepository;
import com.jizza.engine.security.config.SecurityProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionServiceTest {

  @Mock private SessionRepository repository;

  private SessionService service;
  private SecurityProperties props;

  @BeforeEach
  void setUp() {
    props = new SecurityProperties();
    props.getSession().setIdleTimeout(Duration.ofMinutes(30));
    props.getSession().setAbsoluteTimeout(Duration.ofHours(12));
    props.getStepUp().setFreshness(Duration.ofMinutes(5));
    service = new SessionService(repository, props);
    when(repository.save(any(SessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  private SessionEntity live() {
    SessionEntity s = new SessionEntity();
    s.setId(UUID.randomUUID());
    s.setUserId(UUID.randomUUID());
    s.setIssuedAt(Instant.now());
    s.setIdleExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
    s.setAbsoluteExpiresAt(Instant.now().plus(Duration.ofHours(12)));
    return s;
  }

  @Test
  void createsASessionWithBothClocksSet() {
    UUID userId = UUID.randomUUID();
    SessionEntity created = service.create(userId, "Firefox", null);
    assertThat(created.getUserId()).isEqualTo(userId);
    assertThat(created.getIdleExpiresAt()).isAfter(Instant.now());
    assertThat(created.getAbsoluteExpiresAt()).isAfter(created.getIdleExpiresAt());
    assertThat(created.isRevoked()).isFalse();
  }

  @Test
  void slidesTheIdleWindowOnUse() {
    SessionEntity session = live();
    session.setIdleExpiresAt(Instant.now().plus(Duration.ofMinutes(1)));
    when(repository.findById(session.getId())).thenReturn(Optional.of(session));

    var refreshed = service.validateAndTouch(session.getId());
    assertThat(refreshed).isPresent();
    assertThat(refreshed.get().getIdleExpiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(25)));
  }

  @Test
  void rejectsAnIdleExpiredSession() {
    SessionEntity session = live();
    session.setIdleExpiresAt(Instant.now().minusSeconds(1));
    when(repository.findById(session.getId())).thenReturn(Optional.of(session));
    assertThat(service.validateAndTouch(session.getId())).isEmpty();
  }

  @Test
  void rejectsASessionPastItsAbsoluteLifetimeEvenIfRecentlyUsed() {
    SessionEntity session = live();
    session.setAbsoluteExpiresAt(Instant.now().minusSeconds(1));
    when(repository.findById(session.getId())).thenReturn(Optional.of(session));
    assertThat(service.validateAndTouch(session.getId())).isEmpty();
  }

  @Test
  void rejectsARevokedSession() {
    SessionEntity session = live();
    session.setRevoked(true);
    when(repository.findById(session.getId())).thenReturn(Optional.of(session));
    assertThat(service.validateAndTouch(session.getId())).isEmpty();
  }

  @Test
  void stepUpFreshnessExpires() {
    SessionEntity session = live();
    when(repository.findById(session.getId())).thenReturn(Optional.of(session));

    assertThat(service.stepUpFresh(session.getId())).isFalse();

    session.setStepUpAt(Instant.now());
    assertThat(service.stepUpFresh(session.getId())).isTrue();

    session.setStepUpAt(Instant.now().minus(Duration.ofMinutes(10)));
    assertThat(service.stepUpFresh(session.getId())).isFalse();
  }
}

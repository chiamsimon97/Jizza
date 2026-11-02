package com.jizza.engine.security.smartsignin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.UserStatus;
import com.jizza.engine.api.error.AuthException;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import com.jizza.engine.persistence.entity.SessionEntity;
import com.jizza.engine.persistence.entity.UserEntity;
import com.jizza.engine.persistence.repository.UserRepository;
import com.jizza.engine.security.alerts.SecurityAlerts;
import com.jizza.engine.security.config.SecurityProperties;
import com.jizza.engine.security.session.IssuedSession;
import com.jizza.engine.security.session.SessionService;
import com.jizza.engine.security.smartsignin.SmartSignInService.State;
import com.jizza.engine.security.smartsignin.SmartSignInService.Via;
import com.jizza.engine.security.throttle.LoginThrottle;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
class SmartSignInServiceTest {

  private static final String CHROME =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36";

  @Mock private SessionService sessions;
  @Mock private UserRepository users;
  @Mock private WorkspaceDirectory workspaces;
  @Mock private AuditLog audit;
  @Mock private SecurityAlerts alerts;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-02T06:00:00Z"));
  private SecurityProperties props;
  private SmartSignInService service;
  private UserEntity user;
  private InetAddress laptop;
  private InetAddress phone;

  @BeforeEach
  void setUp() throws Exception {
    props = new SecurityProperties();
    service = new SmartSignInService(props, sessions, users, workspaces, new LoginThrottle(props),
        audit, alerts, clock);
    user = new UserEntity();
    user.setId(UUID.randomUUID());
    user.setEmail("chiam@example.com");
    user.setStatus(UserStatus.ACTIVE);
    user.setMfaEnabled(true);
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    when(workspaces.lastActiveWorkspace(any())).thenReturn(Optional.empty());
    when(workspaces.personalWorkspaceOf(any())).thenReturn(UUID.randomUUID());
    when(sessions.create(any(), any(), any(), anyBoolean(), any())).thenAnswer(inv -> {
      SessionEntity s = new SessionEntity();
      s.setId(UUID.randomUUID());
      s.setUserId(inv.getArgument(0));
      s.setMfaSatisfied(inv.getArgument(3));
      return new IssuedSession(s, "session-token");
    });
    laptop = InetAddress.getByName("203.0.113.10");
    phone = InetAddress.getByName("203.0.113.10");
  }

  @Test
  void anApprovedCodeSignsInTheBrowserThatShowedIt() {
    var started = service.start(CHROME, laptop, "203.0.113.10");
    assertThat(service.status(started.id(), started.pollToken())).isEqualTo(State.PENDING);

    var details = service.scan(started.code(), user.getId(), phone);
    assertThat(details.browser()).isEqualTo("Chrome on Windows");
    assertThat(details.ip()).isEqualTo("203.0.113.10");
    assertThat(details.sameNetwork()).isTrue();
    assertThat(service.status(started.id(), started.pollToken())).isEqualTo(State.SCANNED);

    service.decide(started.code(), user.getId(), true, Via.BROWSER, true, "Browser: Chrome on Android");
    assertThat(service.status(started.id(), started.pollToken())).isEqualTo(State.APPROVED);

    var result = service.complete(started.id(), started.pollToken(), CHROME, laptop);
    assertThat(result.userId()).isEqualTo(user.getId());
    assertThat(result.sessionToken()).isEqualTo("session-token");
    verify(sessions).create(eq(user.getId()), eq(CHROME), eq(laptop), eq(true), any());
    verify(alerts).signedIn(user.getId(), user.getEmail(), CHROME, laptop);
  }

  @Test
  void aSessionCanBeCollectedOnlyOnce() {
    var started = approved(Via.BROWSER, true);
    service.complete(started.id(), started.pollToken(), CHROME, laptop);
    assertThatThrownBy(() -> service.complete(started.id(), started.pollToken(), CHROME, laptop))
        .isInstanceOf(JizzaException.class);
  }

  @Test
  void onlyTheBrowserWithThePollTokenCanCollect() {
    var started = approved(Via.BROWSER, true);
    assertThatThrownBy(() -> service.complete(started.id(), "someone-else", CHROME, laptop))
        .isInstanceOf(JizzaException.class);
    assertThatThrownBy(() -> service.status(started.id(), "someone-else"))
        .isInstanceOf(JizzaException.class);
    verify(sessions, never()).create(any(), any(), any(), anyBoolean(), any());
  }

  @Test
  void anUnapprovedCodeGivesNoSession() {
    var started = service.start(CHROME, laptop, "k");
    service.scan(started.code(), user.getId(), phone);
    assertThatThrownBy(() -> service.complete(started.id(), started.pollToken(), CHROME, laptop))
        .isInstanceOf(AuthException.class);
  }

  @Test
  void aDeniedCodeCannotBeApprovedLater() {
    var started = service.start(CHROME, laptop, "k");
    service.decide(started.code(), user.getId(), false, Via.BROWSER, true, "x");
    assertThat(service.status(started.id(), started.pollToken())).isEqualTo(State.DENIED);
    assertThatThrownBy(() ->
        service.decide(started.code(), user.getId(), true, Via.BROWSER, true, "x"))
        .isInstanceOf(JizzaException.class)
        .hasFieldOrPropertyWithValue("type", "smart-sign-in-used");
  }

  @Test
  void aSecondPersonCannotTakeOverAScannedCode() {
    var started = service.start(CHROME, laptop, "k");
    service.scan(started.code(), user.getId(), phone);
    UUID other = UUID.randomUUID();
    assertThatThrownBy(() -> service.scan(started.code(), other, phone))
        .isInstanceOf(JizzaException.class);
    assertThatThrownBy(() -> service.decide(started.code(), other, true, Via.BROWSER, true, "x"))
        .isInstanceOf(JizzaException.class);
  }

  @Test
  void codesExpire() {
    var started = service.start(CHROME, laptop, "k");
    clock.advance(props.getSmartSignIn().getLifetime().plusSeconds(1));
    assertThat(service.status(started.id(), started.pollToken())).isEqualTo(State.EXPIRED);
    assertThatThrownBy(() -> service.scan(started.code(), user.getId(), phone))
        .isInstanceOf(JizzaException.class)
        .hasFieldOrPropertyWithValue("type", "smart-sign-in-expired");
  }

  @Test
  void anApprovalThatWasNotCollectedInTimeExpires() {
    var started = approved(Via.BROWSER, true);
    clock.advance(Duration.ofMinutes(3));
    assertThatThrownBy(() -> service.complete(started.id(), started.pollToken(), CHROME, laptop))
        .isInstanceOf(JizzaException.class);
  }

  @Test
  void aPhoneThatSkippedTwoStepCannotApproveAnAccountThatUsesIt() {
    var started = service.start(CHROME, laptop, "k");
    assertThatThrownBy(() ->
        service.decide(started.code(), user.getId(), true, Via.BROWSER, false, "x"))
        .isInstanceOf(JizzaException.class)
        .hasFieldOrPropertyWithValue("type", "smart-sign-in-needs-two-step");
  }

  @Test
  void aPairedDeviceCountsAsTheSecondFactor() {
    var started = approved(Via.DEVICE, false);
    service.complete(started.id(), started.pollToken(), CHROME, laptop);
    verify(sessions).create(eq(user.getId()), any(), any(), eq(true), any());
  }

  @Test
  void aDisabledAccountStaysOutEvenAfterApproval() {
    var started = approved(Via.BROWSER, true);
    user.setStatus(UserStatus.DISABLED);
    assertThatThrownBy(() -> service.complete(started.id(), started.pollToken(), CHROME, laptop))
        .isInstanceOf(AuthException.class);
  }

  @Test
  void anUnknownCodeIsNotFound() {
    assertThatThrownBy(() -> service.scan("nope", user.getId(), phone))
        .isInstanceOf(JizzaException.class)
        .hasFieldOrPropertyWithValue("type", "smart-sign-in-unknown");
  }

  @Test
  void turnedOffMeansNoCodes() {
    props.getSmartSignIn().setEnabled(false);
    assertThatThrownBy(() -> service.start(CHROME, laptop, "k")).isInstanceOf(JizzaException.class);
  }

  @Test
  void cancellingForgetsTheCode() {
    var started = service.start(CHROME, laptop, "k");
    service.cancel(started.id(), started.pollToken());
    assertThatThrownBy(() -> service.scan(started.code(), user.getId(), phone))
        .isInstanceOf(JizzaException.class);
  }

  private SmartSignInService.Started approved(Via via, boolean mfa) {
    var started = service.start(CHROME, laptop, "k");
    service.decide(started.code(), user.getId(), true, via, mfa, "approver");
    return started;
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) { this.now = now; }

    void advance(Duration d) { now = now.plus(d); }

    @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }

    @Override public Clock withZone(java.time.ZoneId zone) { return this; }

    @Override public Instant instant() { return now; }
  }
}

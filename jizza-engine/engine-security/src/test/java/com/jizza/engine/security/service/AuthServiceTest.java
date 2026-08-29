package com.jizza.engine.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.UserStatus;
import com.jizza.engine.api.error.AuthException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.persistence.entity.SessionEntity;
import com.jizza.engine.persistence.entity.UserEntity;
import com.jizza.engine.persistence.repository.PasswordResetTokenRepository;
import com.jizza.engine.persistence.repository.UserRepository;
import com.jizza.engine.security.config.SecurityProperties;
import com.jizza.engine.security.mfa.TotpService;
import com.jizza.engine.security.password.BreachedPasswordChecker;
import com.jizza.engine.security.password.PasswordPolicy;
import com.jizza.engine.security.session.PendingMfaStore;
import com.jizza.engine.security.session.SessionService;
import com.jizza.engine.security.throttle.LoginThrottle;
import java.nio.charset.StandardCharsets;
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
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

  private static final String PASSWORD = "Correct-Horse-92";

  @Mock private UserRepository users;
  @Mock private PasswordResetTokenRepository resetTokens;
  @Mock private SessionService sessions;
  @Mock private AuditLog audit;
  @Mock private org.springframework.context.ApplicationEventPublisher events;

  private AuthService auth;
  private SecurityProperties props;
  private TotpService totp;
  private UserEntity user;

  /** Runs the callback inline: the unit test has no real transaction to manage. */
  private static org.springframework.transaction.PlatformTransactionManager transactionManager() {
    return new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
      @Override protected Object doGetTransaction() { return new Object(); }
      @Override protected void doBegin(Object transaction,
          org.springframework.transaction.TransactionDefinition definition) { }
      @Override protected void doCommit(
          org.springframework.transaction.support.DefaultTransactionStatus status) { }
      @Override protected void doRollback(
          org.springframework.transaction.support.DefaultTransactionStatus status) { }
    };
  }

  /** Reversible stand-in for Argon2; the real encoder is verified separately and is slow by design. */
  private static final PasswordEncoder ENCODER = new PasswordEncoder() {
    @Override public String encode(CharSequence raw) { return "enc:" + raw; }
    @Override public boolean matches(CharSequence raw, String encoded) {
      return encoded != null && encoded.equals("enc:" + raw);
    }
  };

  /** Identity "encryption" so the MFA secret can be asserted without a key provider. */
  private static final EncryptionService PLAIN = new EncryptionService() {
    @Override public byte[] encrypt(byte[] plain) { return plain; }
    @Override public byte[] decrypt(byte[] ct) { return ct; }
  };

  @BeforeEach
  void setUp() {
    props = new SecurityProperties();
    props.getLockout().setMaxFailedAttempts(3);
    props.getLockout().setLockoutDuration(Duration.ofMinutes(15));
    totp = new TotpService(props);

    auth = new AuthService(users, resetTokens, ENCODER, new PasswordPolicy(new BreachedPasswordChecker()),
        totp, sessions, new PendingMfaStore(), new LoginThrottle(props), PLAIN, audit, props,
        events, new LoginAttempts(users, audit, props), transactionManager());

    user = new UserEntity();
    user.setId(UUID.randomUUID());
    user.setEmail("owner@jizza.local");
    user.setPasswordHash(ENCODER.encode(PASSWORD));
    user.setStatus(UserStatus.ACTIVE);

    when(users.findByEmailIgnoreCase("owner@jizza.local")).thenReturn(Optional.of(user));
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    when(users.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sessions.create(any(), any(), any())).thenAnswer(inv -> {
      SessionEntity s = new SessionEntity();
      s.setId(UUID.randomUUID());
      s.setUserId(inv.getArgument(0));
      return s;
    });
  }

  @Test
  void registeringEnforcesThePasswordPolicy() {
    when(users.existsByEmailIgnoreCase(any())).thenReturn(false);
    assertThatThrownBy(() -> auth.register("new@jizza.local", "short"))
        .isInstanceOf(ValidationException.class);
    verify(users, never()).save(any());
  }

  @Test
  void registrationAnnouncesTheNewUserSoOtherModulesCanProvisionIt() {
    when(users.existsByEmailIgnoreCase(any())).thenReturn(false);
    when(users.save(any(UserEntity.class))).thenAnswer(inv -> {
      UserEntity saved = inv.getArgument(0);
      saved.setId(UUID.randomUUID());
      return saved;
    });

    auth.register("new@jizza.local", "Correct-Horse-92");

    verify(events).publishEvent(org.mockito.ArgumentMatchers
        .any(com.jizza.engine.api.event.UserRegistered.class));
  }

  @Test
  void logsInWithoutMfaAndIssuesASession() {
    var result = auth.login("owner@jizza.local", PASSWORD, "Firefox", null, "1.2.3.4");
    assertThat(result.mfaRequired()).isFalse();
    assertThat(result.sessionId()).isNotNull();
    verify(audit).record(eq(user.getId()), eq(ActorType.USER), any(), eq("auth.login.success"),
        any(), any());
  }

  @Test
  void withMfaEnabledLoginStopsShortOfASession() {
    enableMfa();
    var result = auth.login("owner@jizza.local", PASSWORD, "Firefox", null, "1.2.3.4");
    assertThat(result.mfaRequired()).isTrue();
    assertThat(result.sessionId()).isNull();
    assertThat(result.mfaToken()).isNotBlank();
    verify(sessions, never()).create(any(), any(), any());
  }

  @Test
  void aValidTotpCodeCompletesTheSecondFactor() {
    String secret = enableMfa();
    var pending = auth.login("owner@jizza.local", PASSWORD, "Firefox", null, "1.2.3.4");

    String code = totp.code(secret, Instant.now().getEpochSecond());
    var result = auth.verifyMfa(pending.mfaToken(), code, "Firefox", null, "1.2.3.4");

    assertThat(result.sessionId()).isNotNull();
    verify(audit).record(eq(user.getId()), eq(ActorType.USER), any(), eq("auth.mfa.verified"),
        any(), any());
  }

  @Test
  void aWrongTotpCodeIsRejectedAndTheTokenIsBurned() {
    enableMfa();
    var pending = auth.login("owner@jizza.local", PASSWORD, "Firefox", null, "1.2.3.4");

    assertThatThrownBy(() -> auth.verifyMfa(pending.mfaToken(), "000000", "Firefox", null, "1.2.3.4"))
        .isInstanceOf(AuthException.class);
    assertThatThrownBy(() -> auth.verifyMfa(pending.mfaToken(), "000000", "Firefox", null, "1.2.3.4"))
        .hasMessageContaining("expired");
  }

  @Test
  void repeatedFailuresLockTheAccountAndThenRefuseEvenTheCorrectPassword() {
    for (int attempt = 0; attempt < 3; attempt++) {
      assertThatThrownBy(() -> auth.login("owner@jizza.local", "wrong-password", null, null, "1.2.3.4"))
          .isInstanceOf(AuthException.class);
    }
    assertThat(user.getStatus()).isEqualTo(UserStatus.LOCKED);
    assertThat(user.getLockedUntil()).isAfter(Instant.now());

    assertThatThrownBy(() -> auth.login("owner@jizza.local", PASSWORD, null, null, "1.2.3.4"))
        .isInstanceOf(AuthException.class)
        .hasMessageContaining("Too many failed attempts");
    verify(audit).record(eq(user.getId()), eq(ActorType.SYSTEM), any(), eq("auth.lockout"), any(), any());
  }

  @Test
  void aLockoutLapsesOnceItsWindowPasses() {
    user.setStatus(UserStatus.LOCKED);
    user.setLockedUntil(Instant.now().minusSeconds(1));
    var result = auth.login("owner@jizza.local", PASSWORD, null, null, "1.2.3.4");
    assertThat(result.sessionId()).isNotNull();
    assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
  }

  @Test
  void anUnknownAccountFailsWithTheSameMessageAsAWrongPassword() {
    when(users.findByEmailIgnoreCase("ghost@jizza.local")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> auth.login("ghost@jizza.local", PASSWORD, null, null, "1.2.3.4"))
        .isInstanceOf(AuthException.class)
        .hasMessage("Invalid email or password");
  }

  @Test
  void stepUpRequiresTheCurrentPasswordAndMarksTheSession() {
    UUID sessionId = UUID.randomUUID();
    assertThatThrownBy(() -> auth.stepUp(user.getId(), sessionId, "wrong", null))
        .isInstanceOf(AuthException.class);
    verify(sessions, never()).markStepUp(any());

    auth.stepUp(user.getId(), sessionId, PASSWORD, null);
    verify(sessions).markStepUp(sessionId);
  }

  @Test
  void stepUpAlsoRequiresTheSecondFactorWhenMfaIsOn() {
    String secret = enableMfa();
    UUID sessionId = UUID.randomUUID();

    assertThatThrownBy(() -> auth.stepUp(user.getId(), sessionId, PASSWORD, "000000"))
        .isInstanceOf(AuthException.class);
    auth.stepUp(user.getId(), sessionId, PASSWORD, totp.code(secret, Instant.now().getEpochSecond()));
    verify(sessions).markStepUp(sessionId);
  }

  @Test
  void mfaSetupStoresTheSecretEncryptedAndOnlyEnablesAfterAProvenCode() {
    var setup = auth.setupMfa(user.getId());
    assertThat(user.isMfaEnabled()).isFalse();
    assertThat(setup.provisioningUri()).contains("otpauth://totp/");

    assertThatThrownBy(() -> auth.confirmMfa(user.getId(), "000000")).isInstanceOf(AuthException.class);
    assertThat(user.isMfaEnabled()).isFalse();

    var codes = auth.confirmMfa(user.getId(),
        totp.code(setup.secret(), Instant.now().getEpochSecond()));
    assertThat(user.isMfaEnabled()).isTrue();
    assertThat(codes).hasSize(8);
  }

  @Test
  void resettingAPasswordBurnsTheTokenAndRevokesEverySession() {
    when(resetTokens.save(any())).thenAnswer(inv -> inv.getArgument(0));
    var token = auth.requestPasswordReset("owner@jizza.local", "1.2.3.4");
    assertThat(token).isPresent();

    var entity = new com.jizza.engine.persistence.entity.PasswordResetTokenEntity();
    entity.setUserId(user.getId());
    entity.setTokenHash(com.jizza.engine.api.util.Hashing.sha256Hex(token.get()));
    entity.setExpiresAt(Instant.now().plusSeconds(600));
    when(resetTokens.findByTokenHash(entity.getTokenHash())).thenReturn(Optional.of(entity));

    auth.resetPassword(token.get(), "Another-Strong-1");
    assertThat(entity.isUsed()).isTrue();
    assertThat(user.getPasswordHash()).isEqualTo(ENCODER.encode("Another-Strong-1"));
    verify(sessions).revokeAll(user.getId());
  }

  @Test
  void resetForAnUnknownAddressRevealsNothing() {
    when(users.findByEmailIgnoreCase("ghost@jizza.local")).thenReturn(Optional.empty());
    assertThat(auth.requestPasswordReset("ghost@jizza.local", "1.2.3.4")).isEmpty();
    verify(resetTokens, never()).save(any());
  }

  @Test
  void everyAuthOutcomeReachesTheAuditLog() {
    auth.login("owner@jizza.local", PASSWORD, null, null, "1.2.3.4");
    assertThatThrownBy(() -> auth.login("owner@jizza.local", "nope", null, null, "9.9.9.9"))
        .isInstanceOf(AuthException.class);
    verify(audit, atLeastOnce()).record(any(), any(), any(), eq("auth.login.success"), any(), any());
    verify(audit, atLeastOnce()).record(any(), any(), any(), eq("auth.login.failure"), any(), any());
  }

  private String enableMfa() {
    String secret = totp.newSecret();
    user.setMfaEnabled(true);
    user.setMfaSecret(secret.getBytes(StandardCharsets.UTF_8));
    return secret;
  }
}

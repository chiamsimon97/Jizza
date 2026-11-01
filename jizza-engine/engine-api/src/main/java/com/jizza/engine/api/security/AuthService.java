package com.jizza.engine.api.security;

import com.jizza.engine.api.dto.LoginResult;
import com.jizza.engine.api.dto.MfaSetup;
import com.jizza.engine.api.dto.SecurityStatus;
import com.jizza.engine.api.dto.SessionDto;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Registration, login, MFA, step-up, session lifecycle, recovery and lockout (R3 to R6). */
public interface AuthService {

  default UUID register(String email, String password) {
    return register(RegistrationRequest.individual(email, password));
  }

  /** Creates the account, its personal workspace and, for an organization sign-up, the organization. */
  UUID register(RegistrationRequest request);

  LoginResult login(String email, String password, String device, InetAddress ip, String clientKey);

  LoginResult verifyMfa(String mfaToken, String code, String device, InetAddress ip, String clientKey);

  MfaSetup setupMfa(UUID userId);

  /**
   * Prove a code from the staged authenticator and switch two-step sign-in on. The session that did
   * it counts as having passed the second factor; every other session is signed out.
   *
   * @return fresh one-time recovery codes, shown once
   */
  List<String> confirmMfa(UUID userId, UUID sessionId, String code);

  /** Replace every recovery code with a new set. Old ones stop working at once. */
  List<String> regenerateRecoveryCodes(UUID userId, String code);

  /** Turn two-step sign-in off. Refused when the deployment requires it. */
  void disableMfa(UUID userId, String code);

  SecurityStatus status(UUID userId, UUID sessionId);

  void stepUp(UUID userId, UUID sessionId, String password);

  void logout(UUID userId, UUID sessionId);

  void logoutEverywhere(UUID userId);

  void revokeSession(UUID userId, UUID sessionId);

  List<SessionDto> sessions(UUID userId, UUID currentSessionId);

  Optional<String> requestPasswordReset(String email, String clientKey);

  void resetPassword(String token, String newPassword);

  /** Change the password and sign out every other session, keeping {@code sessionId}. */
  void changePassword(UUID userId, UUID sessionId, String currentPassword, String newPassword);
}

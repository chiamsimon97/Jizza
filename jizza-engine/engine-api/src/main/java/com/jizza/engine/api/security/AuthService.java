package com.jizza.engine.api.security;

import com.jizza.engine.api.dto.LoginResult;
import com.jizza.engine.api.dto.MfaSetup;
import com.jizza.engine.api.dto.SessionDto;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Registration, login, MFA, step-up, session lifecycle, recovery and lockout (R3 to R6). */
public interface AuthService {

  UUID register(String email, String password);

  LoginResult login(String email, String password, String device, InetAddress ip, String clientKey);

  LoginResult verifyMfa(String mfaToken, String code, String device, InetAddress ip, String clientKey);

  MfaSetup setupMfa(UUID userId);

  List<String> confirmMfa(UUID userId, String code);

  void stepUp(UUID userId, UUID sessionId, String password, String code);

  void logout(UUID userId, UUID sessionId);

  void logoutEverywhere(UUID userId);

  void revokeSession(UUID userId, UUID sessionId);

  List<SessionDto> sessions(UUID userId, UUID currentSessionId);

  Optional<String> requestPasswordReset(String email, String clientKey);

  void resetPassword(String token, String newPassword);

  void changePassword(UUID userId, String currentPassword, String newPassword);
}

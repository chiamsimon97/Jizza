package com.jizza.web.api.dto;

import com.jizza.common.dto.AutonomyRuleDto;
import java.util.List;
import java.util.UUID;

public final class Responses {

  private Responses() {}

  /** @param mfaRequired when true the client must call {@code /api/auth/mfa/verify} next. */
  public record LoginResult(boolean mfaRequired, String mfaToken, UUID userId) {}

  public record MfaSetup(String secret, String provisioningUri) {}

  public record RecoveryCodes(List<String> codes) {}

  public record AuthorizeUrl(String url) {}

  public record TestConnection(boolean ok, int httpStatus, String message) {}

  public record Message(String message) {}

  /** Explicit rules plus the default that applies when none of them match. */
  public record AutonomySettings(List<AutonomyRuleDto> policies, String defaultRule,
                                 boolean autonomyPaused) {}

  public record ChainVerification(boolean intact, UUID firstBrokenEntryId, long entries) {}

  public record WebhookEndpoint(UUID id, String url, UUID workflowId, boolean enabled) {}
}

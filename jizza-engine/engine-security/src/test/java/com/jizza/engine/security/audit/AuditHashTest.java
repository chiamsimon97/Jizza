package com.jizza.engine.security.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.util.Json;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditHashTest {

  private final UUID user = UUID.randomUUID();
  private final Instant at = Instant.parse("2026-01-01T00:00:00Z");

  private String hash(String prev, String action, com.fasterxml.jackson.databind.JsonNode detail) {
    return AuditHash.compute(prev, user, ActorType.AGENT, user, action, "target", detail, at);
  }

  /** jsonb hands keys back shortest-first, so the order a caller built them in must not matter. */
  @Test
  void keyOrderInTheDetailDoesNotChangeTheHash() {
    var built = Json.obj().put("workspaceId", "w").put("type", "PERSONAL");
    var readBack = Json.obj().put("type", "PERSONAL").put("workspaceId", "w");
    assertThat(hash(AuditHash.GENESIS, "workspace.created", built))
        .isEqualTo(hash(AuditHash.GENESIS, "workspace.created", readBack));
  }

  @Test
  void isDeterministicForIdenticalContent() {
    assertThat(hash(AuditHash.GENESIS, "tool.call", null))
        .isEqualTo(hash(AuditHash.GENESIS, "tool.call", null));
  }

  @Test
  void changesWhenAnyFieldChanges() {
    String base = hash(AuditHash.GENESIS, "tool.call", null);
    assertThat(hash(AuditHash.GENESIS, "tool.other", null)).isNotEqualTo(base);
    assertThat(hash("different-prev", "tool.call", null)).isNotEqualTo(base);
    assertThat(hash(AuditHash.GENESIS, "tool.call", Json.obj().put("amount", 5))).isNotEqualTo(base);
  }

  @Test
  void editingAnEntryBreaksEveryLinkAfterIt() {
    String first = hash(AuditHash.GENESIS, "auth.login", null);
    String second = AuditHash.compute(first, user, ActorType.USER, user, "tool.call", "t", null, at);
    String third = AuditHash.compute(second, user, ActorType.USER, user, "run.start", "t", null, at);

    String tamperedFirst = hash(AuditHash.GENESIS, "auth.login.tampered", null);
    String recomputedSecond =
        AuditHash.compute(tamperedFirst, user, ActorType.USER, user, "tool.call", "t", null, at);
    String recomputedThird =
        AuditHash.compute(recomputedSecond, user, ActorType.USER, user, "run.start", "t", null, at);

    assertThat(recomputedSecond).isNotEqualTo(second);
    assertThat(recomputedThird).isNotEqualTo(third);
  }
}

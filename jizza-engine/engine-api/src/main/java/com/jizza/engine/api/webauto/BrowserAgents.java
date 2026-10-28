package com.jizza.engine.api.webauto;

import com.jizza.engine.api.authz.Access;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Browser devices available to design-time tools, without exposing the rest of their manifest. */
public interface BrowserAgents {

  record Browser(UUID id, UUID workspaceId, String name, boolean online, String family, String version,
                 List<String> allowedSites, Integer sessions, Instant lastSeenAt) {}

  List<Browser> list(Access access);
}

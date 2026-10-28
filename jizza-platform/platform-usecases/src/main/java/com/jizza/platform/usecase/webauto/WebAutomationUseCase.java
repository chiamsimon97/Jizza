package com.jizza.platform.usecase.webauto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.webauto.BrowserAgents;
import com.jizza.engine.api.webauto.DesignTimeService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Manual web-step design actions, kept behind device ownership checks in the engine ports. */
@Service
public class WebAutomationUseCase {

  private final BrowserAgents browsers;
  private final DesignTimeService designTime;
  private final AuditLog audit;

  public WebAutomationUseCase(BrowserAgents browsers, DesignTimeService designTime, AuditLog audit) {
    this.browsers = browsers;
    this.designTime = designTime;
    this.audit = audit;
  }

  public List<BrowserAgents.Browser> browsers(Access access) {
    return browsers.list(access);
  }

  public DesignTimeService.Result testStep(Access access, UUID deviceId, String action,
                                           Integer version, JsonNode inputs) {
    DesignTimeService.Result result =
        designTime.testStep(access, deviceId, action, version, inputs);
    audit.record(access.userId(), ActorType.USER, null, "web.step_tested", action,
        Json.obj().put("deviceId", deviceId.toString()).put("status", result.status()));
    return result;
  }
}

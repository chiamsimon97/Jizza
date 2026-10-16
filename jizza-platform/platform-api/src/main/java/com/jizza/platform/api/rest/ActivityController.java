package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.dto.AuditEntryDto;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Responses;
import com.jizza.platform.usecase.activity.ActivityUseCase;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The activity view: what the agents actually did, read from the audit log (R14/R56). */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/activity")
public class ActivityController {

  private final ActivityUseCase activity;

  public ActivityController(ActivityUseCase activity) {
    this.activity = activity;
  }

  @GetMapping
  public List<AuditEntryDto> recent(@RequestParam(defaultValue = "100") int limit,
                                    @RequestParam(required = false) String action) {
    return activity.recent(CurrentUser.userId(), limit, action);
  }

  @GetMapping("/verify")
  public Responses.ChainVerification verify() {
    var result = activity.verify(CurrentUser.userId());
    return new Responses.ChainVerification(result.intact(), result.firstBrokenEntryId(),
        result.entries());
  }
}

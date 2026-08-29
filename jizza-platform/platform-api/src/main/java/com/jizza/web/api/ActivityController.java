package com.jizza.web.api;

import com.jizza.common.dto.AuditEntryDto;
import com.jizza.security.session.CurrentUser;
import com.jizza.web.activity.ActivityService;
import com.jizza.web.api.dto.Responses;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The activity view: what the agents actually did, read from the audit log (R14/R56). */
@RestController
@RequestMapping("/api/activity")
public class ActivityController {

  private final ActivityService activity;

  public ActivityController(ActivityService activity) {
    this.activity = activity;
  }

  @GetMapping
  public List<AuditEntryDto> recent(@RequestParam(defaultValue = "100") int limit,
                                    @RequestParam(required = false) String action) {
    return activity.recent(CurrentUser.userId(), limit, action);
  }

  @GetMapping("/verify")
  public Responses.ChainVerification verify() {
    return activity.verify(CurrentUser.userId());
  }
}

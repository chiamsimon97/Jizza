package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.audit.catalog.ActivityLogType.Severity;
import com.jizza.engine.api.dto.AuditEntryDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.util.TimeBuckets;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Responses;
import com.jizza.platform.usecase.activity.ActivityExplorer;
import com.jizza.platform.usecase.activity.ActivityUseCase;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
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

  /** Every category and sub-group the activity view can filter on. */
  @GetMapping("/catalog")
  public ActivityExplorer.Catalog catalog() {
    return activity.catalog();
  }

  /** A time window of activity, filtered, with counts for each filter and a timeline. */
  @GetMapping("/explore")
  public ActivityExplorer.Page explore(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(required = false) Set<String> category,
      @RequestParam(required = false) Set<String> group,
      @RequestParam(required = false) Set<Severity> severity,
      @RequestParam(required = false) Set<ActorType> actor,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(required = false) String tz) {
    return activity.explore(CurrentUser.userId(), new ActivityExplorer.Query(from, to, category,
        group, severity, actor, q, limit, offset, TimeBuckets.zoneOf(tz)));
  }

  @GetMapping("/verify")
  public Responses.ChainVerification verify() {
    var result = activity.verify(CurrentUser.userId());
    return new Responses.ChainVerification(result.intact(), result.firstBrokenEntryId(),
        result.entries());
  }
}

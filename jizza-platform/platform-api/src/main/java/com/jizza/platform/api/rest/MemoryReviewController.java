package com.jizza.platform.api.rest;

import com.jizza.engine.api.memory.MemoryReviews;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The memory review: suggestions Jizza makes after reading the user's recent chats and failures,
 * waiting for the user to approve or dismiss each one. Switching the nightly review on or off is
 * part of the memory settings ({@code PUT /api/memory/settings}, {@code review}).
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/memory/review")
public class MemoryReviewController {

  private final MemoryReviews reviews;

  public MemoryReviewController(MemoryReviews reviews) {
    this.reviews = reviews;
  }

  /** Whether it is on, the latest review, how many suggestions wait, and when it runs next. */
  @GetMapping
  public MemoryReviews.Status status() {
    return reviews.status(CurrentUser.userId());
  }

  /** Reviews the activity since the last review now, instead of waiting for tonight. */
  @PostMapping("/run")
  public MemoryReviews.Review run() {
    return reviews.runNow(CurrentUser.userId());
  }

  /** Suggestions by status: open (the default), approved, dismissed, expired, stale. */
  @GetMapping("/proposals")
  public List<MemoryReviews.Proposal> proposals(@RequestParam(required = false) List<String> status,
                                                @RequestParam(required = false) Integer limit) {
    return reviews.proposals(CurrentUser.userId(), status, limit == null ? 50 : limit);
  }

  /** Applies a suggestion, with the wording the user edited it to when they did. */
  @PostMapping("/proposals/{id}/approve")
  public MemoryReviews.Proposal approve(@PathVariable UUID id,
                                        @Valid @RequestBody(required = false) Approval body) {
    return reviews.approve(CurrentUser.userId(), id, body == null ? null : body.content());
  }

  @PostMapping("/proposals/{id}/dismiss")
  public MemoryReviews.Proposal dismiss(@PathVariable UUID id) {
    return reviews.dismiss(CurrentUser.userId(), id);
  }

  public record Approval(@Size(max = 2000) String content) {}
}

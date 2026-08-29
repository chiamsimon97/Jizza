package com.jizza.web.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.platform.lifecycle.DataLifecycleService;
import com.jizza.security.session.CurrentUser;
import com.jizza.web.api.dto.Responses;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Export and "forget everything" (R48). Both are step-up guarded; the second is irreversible. */
@RestController
@RequestMapping("/api/data")
@com.jizza.common.util.StepUpRequired
public class DataController {

  private static final String CONFIRMATION = "DELETE EVERYTHING";

  private final DataLifecycleService lifecycle;

  public DataController(DataLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  @GetMapping("/export")
  public ResponseEntity<ObjectNode> export() {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"jizza-export.json\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(lifecycle.export(CurrentUser.userId()));
  }

  /**
   * @param confirm must be the exact phrase; a typed confirmation is the last guard on something
   *                that cannot be undone
   */
  @DeleteMapping
  public Responses.Message hardDelete(@RequestParam String confirm) {
    if (!CONFIRMATION.equals(confirm)) {
      throw new com.jizza.common.error.ValidationException(
          "To delete everything, send confirm=" + CONFIRMATION);
    }
    lifecycle.hardDelete(CurrentUser.userId());
    return new Responses.Message("Everything has been deleted.");
  }
}

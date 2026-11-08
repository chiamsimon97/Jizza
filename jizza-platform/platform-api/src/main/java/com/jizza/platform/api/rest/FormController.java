package com.jizza.platform.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.form.Forms;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.PublicEndpoint;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Responses;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Forms: the builder's routes, and the two public ones a published form's link uses. The public
 * routes know a form only by its unguessable token and show nothing of its owner.
 */
@RestController
public class FormController {

  public record WorkflowLinks(List<UUID> workflowIds) {}

  private final Forms forms;

  public FormController(Forms forms) {
    this.forms = forms;
  }

  @PublicEndpoint
  @GetMapping("/api/public/forms/{token}")
  public Forms.PublicForm publicForm(@PathVariable String token) {
    return forms.publicForm(token);
  }

  @PublicEndpoint
  @PostMapping("/api/public/forms/{token}/submit")
  public Forms.SubmitResult submit(@PathVariable String token, @RequestBody(required = false) JsonNode data,
                                   HttpServletRequest request) {
    return forms.submit(token, data, request.getRemoteAddr());
  }

  @RestController
  @AuthenticatedOnly
  @RequestMapping("/api/forms")
  public static class Builder {

    private final Forms forms;

    public Builder(Forms forms) {
      this.forms = forms;
    }

    @GetMapping
    public List<Forms.FormDto> list() {
      return forms.list(CurrentUser.userId());
    }

    @GetMapping("/{id}")
    public Forms.FormDto get(@PathVariable UUID id) {
      return forms.get(CurrentUser.userId(), id);
    }

    @PostMapping
    public Forms.FormDto create(@RequestBody Forms.FormDraft draft) {
      return forms.create(CurrentUser.userId(), draft);
    }

    @PutMapping("/{id}")
    public Forms.FormDto update(@PathVariable UUID id, @RequestBody Forms.FormDraft draft) {
      return forms.update(CurrentUser.userId(), id, draft);
    }

    @DeleteMapping("/{id}")
    public Responses.Message delete(@PathVariable UUID id) {
      forms.delete(CurrentUser.userId(), id);
      return new Responses.Message("Form deleted.");
    }

    @PostMapping("/{id}/publish")
    public Forms.FormDto publish(@PathVariable UUID id) {
      return forms.publish(CurrentUser.userId(), id);
    }

    @PostMapping("/{id}/unpublish")
    public Forms.FormDto unpublish(@PathVariable UUID id) {
      return forms.unpublish(CurrentUser.userId(), id);
    }

    /** The workflows this form starts when it is submitted. */
    @PutMapping("/{id}/workflows")
    public Forms.FormDto setWorkflows(@PathVariable UUID id, @RequestBody WorkflowLinks links) {
      return forms.setWorkflows(CurrentUser.userId(), id, links.workflowIds());
    }

    @GetMapping("/{id}/submissions")
    public List<Forms.Submission> submissions(@PathVariable UUID id,
                                              @RequestParam(defaultValue = "100") int limit) {
      return forms.submissions(CurrentUser.userId(), id, limit);
    }

    /** One file someone attached, downloaded rather than shown, whatever its type. */
    @GetMapping("/{id}/submissions/{submissionId}/files/{field}")
    public ResponseEntity<byte[]> file(@PathVariable UUID id, @PathVariable UUID submissionId,
                                       @PathVariable String field) {
      Forms.FileContent file = forms.file(CurrentUser.userId(), id, submissionId, field);
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_OCTET_STREAM)
          .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
              .filename(file.name(), StandardCharsets.UTF_8).build().toString())
          .header("X-Content-Type-Options", "nosniff")
          .body(file.bytes());
    }
  }
}

package com.jizza.platform.api.rest.vita;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.vita.documents.VitaDocumentUseCase;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Vita's documents vault and extraction review (spec 5.12, Phase 1b), behind the health switch.
 * Every route reaches only the caller's own household; another account's document reads as not
 * found. Nothing read from a document is saved to the record until {@code /confirm}.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/vita")
@ConditionalOnProperty(name = "jizza.health.enabled", havingValue = "true")
public class VitaDocumentController {

  private final VitaDocumentUseCase documents;

  public VitaDocumentController(VitaDocumentUseCase documents) {
    this.documents = documents;
  }

  /** A PDF or a photo, with optional details (kind, title, documentDate, doctorId, conditionId, private). */
  @PostMapping(value = "/people/{personId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public ObjectNode upload(@PathVariable UUID personId, @RequestParam("file") MultipartFile file,
                           @RequestParam(value = "kind", required = false) String kind,
                           @RequestParam(value = "title", required = false) String title,
                           @RequestParam(value = "documentDate", required = false) String documentDate,
                           @RequestParam(value = "doctorId", required = false) String doctorId,
                           @RequestParam(value = "conditionId", required = false) String conditionId,
                           @RequestParam(value = "private", required = false) Boolean isPrivate) {
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new ValidationException("The file could not be read.");
    }
    ObjectNode meta = Json.obj();
    if (kind != null && !kind.isBlank()) meta.put("kind", kind);
    if (title != null && !title.isBlank()) meta.put("title", title);
    if (documentDate != null && !documentDate.isBlank()) meta.put("documentDate", documentDate);
    if (doctorId != null && !doctorId.isBlank()) meta.put("doctorId", doctorId);
    if (conditionId != null && !conditionId.isBlank()) meta.put("conditionId", conditionId);
    if (isPrivate != null) meta.put("private", isPrivate);
    return documents.upload(CurrentUser.userId(), personId, file.getOriginalFilename(), file.getContentType(),
        bytes, meta, "upload");
  }

  @GetMapping("/people/{personId}/documents")
  public List<ObjectNode> list(@PathVariable UUID personId) {
    return documents.list(CurrentUser.userId(), personId);
  }

  @GetMapping("/documents/{id}")
  public ObjectNode get(@PathVariable UUID id) {
    return documents.get(CurrentUser.userId(), id);
  }

  /** The stored file, shown beside the draft in the review. Never cached. */
  @GetMapping("/documents/{id}/file")
  public ResponseEntity<byte[]> file(@PathVariable UUID id) {
    UUID caller = CurrentUser.userId();
    byte[] bytes = documents.file(caller, id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(documents.contentType(caller, id)))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("document").build().toString())
        .cacheControl(CacheControl.noStore())
        .header("Content-Security-Policy", "sandbox; default-src 'none'")
        .body(bytes);
  }

  @PutMapping("/documents/{id}")
  public ObjectNode update(@PathVariable UUID id, @RequestBody JsonNode body) {
    return documents.update(CurrentUser.userId(), id, body);
  }

  @DeleteMapping("/documents/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    documents.delete(CurrentUser.userId(), id);
  }

  @PostMapping("/documents/{id}/move")
  public ObjectNode move(@PathVariable UUID id, @RequestBody JsonNode body) {
    return documents.move(CurrentUser.userId(), id, body);
  }

  /** Reads the document into a draft; {@code password} opens a locked PDF and is kept encrypted. */
  @PostMapping("/documents/{id}/extract")
  public ObjectNode extract(@PathVariable UUID id, @RequestBody(required = false) JsonNode body) {
    return documents.extract(CurrentUser.userId(), id, body);
  }

  @GetMapping("/documents/{id}/draft")
  public ObjectNode draft(@PathVariable UUID id) {
    return documents.draft(CurrentUser.userId(), id);
  }

  /** Saves the reviewed draft: {@code draft} (as edited), {@code patientConfirmed}, {@code doctorId}. */
  @PostMapping("/documents/{id}/confirm")
  public ObjectNode confirm(@PathVariable UUID id, @RequestBody(required = false) JsonNode body) {
    return documents.confirm(CurrentUser.userId(), id, body);
  }

  @DeleteMapping("/documents/{id}/draft")
  public ObjectNode discard(@PathVariable UUID id) {
    return documents.discard(CurrentUser.userId(), id);
  }
}

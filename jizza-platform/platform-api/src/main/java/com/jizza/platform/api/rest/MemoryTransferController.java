package com.jizza.platform.api.rest;

import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.usecase.memory.MemoryTransferUseCase;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Exporting the user's memory as Markdown, and importing memory from another assistant. */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/memory")
public class MemoryTransferController {

  private final MemoryTransferUseCase transfer;

  public MemoryTransferController(MemoryTransferUseCase transfer) {
    this.transfer = transfer;
  }

  @GetMapping("/export")
  public ResponseEntity<byte[]> export() {
    MemoryTransferUseCase.Export export = transfer.export(CurrentUser.userId());
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(export.filename()).build().toString())
        // Personal data: never cached.
        .cacheControl(CacheControl.noStore())
        .body(export.zip());
  }

  /** The prompt to give another assistant so it writes out what it remembers. */
  @GetMapping("/import/prompt")
  public Map<String, String> importPrompt() {
    return Map.of("prompt", transfer.importPrompt());
  }

  @PostMapping("/import")
  public MemoryTransferUseCase.ImportResult importText(@Valid @RequestBody Requests.MemoryImport input) {
    return transfer.importText(CurrentUser.userId(), input.text(), input.provider());
  }

  @PostMapping(value = "/import/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public MemoryTransferUseCase.ImportResult importFile(@RequestParam("file") MultipartFile file,
                                                       @RequestParam(required = false) String provider) {
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new ValidationException("The upload could not be read");
    }
    return transfer.importFile(CurrentUser.userId(), file.getOriginalFilename(), bytes, provider);
  }
}

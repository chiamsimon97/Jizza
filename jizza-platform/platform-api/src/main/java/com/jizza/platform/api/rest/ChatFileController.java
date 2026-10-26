package com.jizza.platform.api.rest;

import com.jizza.engine.api.chat.ChatFiles;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Files attached to a chat message. The upload returns an id that the message refers to; the
 * model then reads the PDF or photo itself when the message is answered.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/chat/files")
public class ChatFileController {

  private final ChatFiles files;

  public ChatFileController(ChatFiles files) {
    this.files = files;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ChatFiles.Upload upload(@RequestParam("file") MultipartFile file) {
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new ValidationException("The upload could not be read");
    }
    return files.add(CurrentUser.userId(), file.getOriginalFilename(), file.getContentType(), bytes);
  }
}

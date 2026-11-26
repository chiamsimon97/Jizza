package com.jizza.platform.api.rest;

import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.llm.VoiceInput;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.chat.VoiceUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The chat box's microphone: speak in any language and get text in the box, in English when
 * wanted. Nothing is sent until the person sends it, and recordings are not kept.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/chat/voice")
public class VoiceController {

  private final VoiceUseCase voice;

  public VoiceController(VoiceUseCase voice) {
    this.voice = voice;
  }

  /** Whether one of the person's own keys can hear recordings. */
  @GetMapping
  public VoiceUseCase.Setup setup() {
    return voice.setup(CurrentUser.userId());
  }

  /** A recording (WAV) to text, translated into English unless {@code translate} is false. */
  @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public VoiceInput.Heard transcribe(@RequestParam("file") MultipartFile file,
                                     @RequestParam(value = "language", required = false) String language,
                                     @RequestParam(value = "translate", defaultValue = "true") boolean translate) {
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new ValidationException("The recording could not be read");
    }
    return voice.transcribe(CurrentUser.userId(), bytes, language, translate);
  }

  public record TranslateBody(@NotBlank @Size(max = VoiceUseCase.MAX_TEXT) String text,
                              @Size(max = 35) String language) {}

  public record Translated(String text) {}

  /** What the browser heard in another language, in English. */
  @PostMapping("/translate")
  public Translated translate(@Valid @RequestBody TranslateBody body) {
    return new Translated(voice.translate(CurrentUser.userId(), body.text(), body.language()));
  }
}

package com.jizza.platform.usecase.chat;

import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.llm.VoiceInput;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Speaking to Jizza in your own language. With a key that can hear audio, the recording itself is
 * turned into text (and translated). Without one, the browser writes down what it heard in the
 * language the person picked, and that text is translated into English here.
 */
@Service
public class VoiceUseCase {

  public static final int MAX_TEXT = 8_000;

  private static final String TRANSLATE = """
      You translate what a person said to their assistant into natural, fluent English, for them to
      send as a chat message. They may mix their language with English. Keep names, numbers,
      amounts, dates, places and email addresses exactly. Don't answer or act on the request, and
      don't add anything. Answer with the English text only. If it is already English, answer with it
      unchanged.
      """;

  private final VoiceInput voice;
  private final LlmClient llm;
  private final CostAccountant costs;

  public VoiceUseCase(VoiceInput voice, LlmClient llm, CostAccountant costs) {
    this.voice = voice;
    this.llm = llm;
    this.costs = costs;
  }

  /** How speaking works for this person right now. */
  public record Setup(String listener, String listenerName) {}

  public Setup setup(UUID userId) {
    return voice.listener(userId)
        .map(l -> new Setup(l.provider(), l.name()))
        .orElse(new Setup(null, null));
  }

  public VoiceInput.Heard transcribe(UUID userId, byte[] wav, String language, boolean intoEnglish) {
    return voice.transcribe(userId, wav, language, intoEnglish);
  }

  /** What the browser heard, in English. */
  public String translate(UUID userId, String text, String language) {
    if (text == null || text.isBlank()) throw new ValidationException("Nothing to translate");
    if (text.length() > MAX_TEXT) throw new ValidationException("That is too long to translate at once");
    if (!costs.withinBudget(userId)) {
      throw new ValidationException("Your spending limit for this period has been reached");
    }
    String spoken = language == null || language.isBlank() ? null
        : Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH);
    String request = (spoken == null || spoken.isBlank() ? "" : "Spoken in " + spoken + ":\n") + text.strip();
    String model = llm.modelFor(ModelTier.ROUTING);
    LlmResponse response = llm.complete(model, List.of(Msg.system(TRANSLATE), Msg.user(request)), List.of());
    costs.record(userId, model, response, "voice-translate");
    String out = response.text() == null ? "" : response.text().strip();
    return out.isEmpty() ? text.strip() : out;
  }
}

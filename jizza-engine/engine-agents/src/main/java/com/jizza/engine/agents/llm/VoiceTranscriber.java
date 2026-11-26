package com.jizza.engine.agents.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.llm.ModelRefs;
import com.jizza.engine.api.llm.VoiceInput;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.Redaction;
import com.jizza.engine.persistence.entity.LlmProviderEntity;
import com.jizza.engine.persistence.repository.LlmProviderRepository;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Hears a short recording with the person's own OpenAI, Gemini or OpenRouter key, so they can
 * speak in their own language (Hindi, Tamil, a mix with English...) and get text for the chat box,
 * translated into English when they want.
 *
 * <p>OpenAI uses its speech endpoints (Whisper translates straight into English). Gemini, directly
 * or through OpenRouter, hears the audio in a chat call and is told to write or translate it, which
 * copes well with people who switch languages mid-sentence.
 */
@Service
public class VoiceTranscriber implements VoiceInput {

  /** Providers that can hear audio, in the order they are preferred. */
  static final List<String> HEARING = List.of(ModelRefs.GEMINI, ModelRefs.OPENAI, ModelRefs.OPENROUTER);
  static final int MAX_BYTES = 10 * 1024 * 1024;

  static final String GEMINI_MODEL = "gemini-2.5-flash";
  static final String OPENROUTER_MODEL = "google/gemini-2.5-flash";
  static final String OPENAI_TRANSLATE_MODEL = "whisper-1";
  static final String OPENAI_TRANSCRIBE_MODEL = "gpt-4o-mini-transcribe";

  private final LlmProviderRepository providers;
  private final EncryptionService encryption;

  public VoiceTranscriber(LlmProviderRepository providers, EncryptionService encryption) {
    this.providers = providers;
    this.encryption = encryption;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Listener> listener(UUID userId) {
    return hearing(userId).map(e -> new Listener(e.getProvider(), ModelProviders.kind(e.getProvider()).name()));
  }

  private Optional<LlmProviderEntity> hearing(UUID userId) {
    if (userId == null) return Optional.empty();
    for (String p : HEARING) {
      var found = providers.findByUserIdAndProvider(userId, p)
          .filter(LlmProviderEntity::isEnabled).filter(e -> e.getApiKey() != null);
      if (found.isPresent()) return found;
    }
    return Optional.empty();
  }

  @Override
  @Transactional(readOnly = true)
  public Heard transcribe(UUID userId, byte[] wav, String language, boolean intoEnglish) {
    if (wav == null || wav.length < 100) throw new ValidationException("The recording was empty");
    if (wav.length > MAX_BYTES) throw new ValidationException("That recording is too long; keep it under two minutes");
    LlmProviderEntity entity = hearing(userId).orElseThrow(() -> new JizzaException(
        HttpStatus.BAD_REQUEST, "voice-no-provider", "Voice needs a key",
        "To speak in your own language, connect OpenAI, Google Gemini or OpenRouter under Settings, AI models."));
    String provider = entity.getProvider();
    String key = encryption.decryptString(entity.getApiKey());
    String base = OpenAiCompatibleLlmClient.trimSlash(entity.getBaseUrl() == null || entity.getBaseUrl().isBlank()
        ? OpenAiCompatibleLlmClient.defaultBaseUrl(provider) : entity.getBaseUrl());
    RestClient http = RestClient.builder()
        .requestFactory(AnthropicLlmClient.requestFactory(Duration.ofSeconds(60)))
        .defaultHeader("Authorization", "Bearer " + key)
        .build();
    String text;
    try {
      text = ModelRefs.OPENAI.equals(provider)
          ? openAi(http, base, wav, language, intoEnglish)
          : chatWithAudio(http, base, ModelRefs.GEMINI.equals(provider) ? GEMINI_MODEL : OPENROUTER_MODEL,
              wav, language, intoEnglish);
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      String why = status == 401 || status == 403 ? "it didn't accept your key"
          : status == 429 ? "it is busy or your credit ran out" : "it answered " + status;
      throw new JizzaException(HttpStatus.BAD_GATEWAY, "voice-provider-failed", "Couldn't hear that",
          ModelProviders.kind(provider).name() + " couldn't turn the recording into text: " + why + ". "
              + Redaction.scrub(abbreviate(e.getResponseBodyAsString())));
    } catch (RuntimeException e) {
      throw new JizzaException(HttpStatus.BAD_GATEWAY, "voice-provider-failed", "Couldn't hear that",
          "Could not reach " + ModelProviders.kind(provider).name() + ": " + e.getMessage());
    }
    return new Heard(clean(text), provider);
  }

  private static String openAi(RestClient http, String base, byte[] wav, String language, boolean intoEnglish) {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType("audio/wav"));
    fileHeaders.setContentDispositionFormData("file", "speech.wav");
    form.add("file", new HttpEntity<>(new ByteArrayResource(wav) {
      @Override
      public String getFilename() { return "speech.wav"; }
    }, fileHeaders));
    form.add("model", intoEnglish ? OPENAI_TRANSLATE_MODEL : OPENAI_TRANSCRIBE_MODEL);
    form.add("response_format", "json");
    String lang = isoLanguage(language);
    if (!intoEnglish && lang != null) form.add("language", lang);
    JsonNode body = http.post()
        .uri(URI.create(base + (intoEnglish ? "/audio/translations" : "/audio/transcriptions")))
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(form)
        .retrieve().body(JsonNode.class);
    return body == null ? "" : body.path("text").asText("");
  }

  private static String chatWithAudio(RestClient http, String base, String model, byte[] wav,
                                      String language, boolean intoEnglish) {
    ObjectNode req = Json.obj();
    req.put("model", model);
    req.put("temperature", 0);
    ArrayNode messages = req.putArray("messages");
    ObjectNode user = messages.addObject();
    user.put("role", "user");
    ArrayNode content = user.putArray("content");
    content.addObject().put("type", "text").put("text", instruction(language, intoEnglish));
    ObjectNode audio = content.addObject();
    audio.put("type", "input_audio");
    audio.putObject("input_audio").put("data", Base64.getEncoder().encodeToString(wav)).put("format", "wav");
    JsonNode body = http.post().uri(URI.create(base + "/chat/completions"))
        .contentType(MediaType.APPLICATION_JSON)
        .body(req)
        .retrieve().body(JsonNode.class);
    if (body == null) return "";
    return body.path("choices").path(0).path("message").path("content").asText("");
  }

  /** What the listening model is asked to do with the recording. */
  static String instruction(String language, boolean intoEnglish) {
    String spoken = languageName(language);
    StringBuilder sb = new StringBuilder(
        "This is a recording of a person giving a request to their assistant. They may speak any "
            + "language, including Indian languages such as Hindi, Tamil, Telugu, Kannada, Malayalam, "
            + "Marathi, Bengali, Gujarati, Punjabi or Urdu, and may mix languages with English. ");
    if (spoken != null) sb.append("They said they would mostly speak ").append(spoken).append(". ");
    if (intoEnglish) {
      sb.append("Write what they said as natural, fluent English, translating every part that is not "
          + "in English. ");
    } else {
      sb.append("Write exactly what they said, in the language and script they spoke it in. ");
    }
    sb.append("Keep names, numbers, amounts, dates, places and email addresses exactly. Leave out "
        + "fillers like um and uh. Answer with that text only: no quotes, notes or labels. If nobody "
        + "spoke, answer with nothing.");
    return sb.toString();
  }

  /** The two-letter code OpenAI takes, or null for "detect it". */
  static String isoLanguage(String tag) {
    if (tag == null || tag.isBlank() || tag.equalsIgnoreCase("auto")) return null;
    String lang = Locale.forLanguageTag(tag.trim()).getLanguage();
    return lang == null || lang.length() != 2 ? null : lang;
  }

  static String languageName(String tag) {
    String iso = isoLanguage(tag);
    if (iso == null) return null;
    String name = Locale.forLanguageTag(iso).getDisplayLanguage(Locale.ENGLISH);
    return name == null || name.isBlank() || name.equalsIgnoreCase(iso) ? null : name;
  }

  /** The text without the quotes or labels a model sometimes adds anyway. */
  static String clean(String text) {
    if (text == null) return "";
    String t = text.strip();
    if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1).strip();
    return t.length() > 16_000 ? t.substring(0, 16_000) : t;
  }

  private static String abbreviate(String s) {
    if (s == null) return "";
    return s.length() > 300 ? s.substring(0, 300) + "…" : s;
  }
}

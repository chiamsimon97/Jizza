package com.jizza.engine.api.llm;

import java.util.Optional;
import java.util.UUID;

/**
 * Speaking to Jizza in any language: a short recording is turned into text by one of the AI
 * providers the person connected with their own key, which can hear audio (OpenAI, Google Gemini
 * or OpenRouter). The text can come back as spoken or translated into English.
 */
public interface VoiceInput {

  /** The connected provider that will hear recordings, if there is one. */
  record Listener(String provider, String name) {}

  Optional<Listener> listener(UUID userId);

  /** What was heard, and which provider heard it. */
  record Heard(String text, String provider) {}

  /**
   * Turns a recording (a WAV file) into text.
   *
   * @param language the language spoken, as a BCP-47 tag such as {@code hi-IN}, or blank to detect it
   * @param intoEnglish whether to translate what was said into English
   */
  Heard transcribe(UUID userId, byte[] wav, String language, boolean intoEnglish);
}

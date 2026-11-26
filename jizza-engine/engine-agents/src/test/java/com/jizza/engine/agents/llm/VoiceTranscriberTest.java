package com.jizza.engine.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.llm.ModelRefs;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.LlmProviderEntity;
import com.jizza.engine.persistence.repository.LlmProviderRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VoiceTranscriberTest {

  private final UUID user = UUID.randomUUID();
  private LlmProviderRepository repo;
  private VoiceTranscriber voice;
  private HttpServer server;
  private final AtomicReference<String> path = new AtomicReference<>();
  private final AtomicReference<String> auth = new AtomicReference<>();
  private final AtomicReference<String> body = new AtomicReference<>();
  private volatile int status = 200;
  private volatile String answer = "{}";
  private final byte[] wav = new byte[2048];

  @BeforeEach
  void setUp() throws Exception {
    repo = mock(LlmProviderRepository.class);
    when(repo.findByUserIdAndProvider(any(), anyString())).thenReturn(Optional.empty());
    EncryptionService enc = mock(EncryptionService.class);
    when(enc.decryptString(any())).thenReturn("sk-test");
    voice = new VoiceTranscriber(repo, enc);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      path.set(ex.getRequestURI().getPath());
      auth.set(ex.getRequestHeaders().getFirst("Authorization"));
      body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
      byte[] out = answer.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(status, out.length);
      ex.getResponseBody().write(out);
      ex.close();
    });
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private void connect(String provider) {
    LlmProviderEntity e = new LlmProviderEntity();
    e.setId(UUID.randomUUID());
    e.setUserId(user);
    e.setProvider(provider);
    e.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    e.setApiKey(new byte[] {1});
    e.setEnabled(true);
    when(repo.findByUserIdAndProvider(user, provider)).thenReturn(Optional.of(e));
  }

  @Test
  void noKeyThatHearsMeansNoListener() {
    connect(ModelRefs.ANTHROPIC);
    assertThat(voice.listener(user)).isEmpty();
    assertThatThrownBy(() -> voice.transcribe(user, wav, "hi-IN", true))
        .isInstanceOf(JizzaException.class).hasMessageContaining("Settings, AI models");
  }

  @Test
  void geminiHearsTheRecordingAndTranslates() {
    connect(ModelRefs.GEMINI);
    connect(ModelRefs.OPENAI);
    assertThat(voice.listener(user)).get().extracting(l -> l.provider()).isEqualTo("gemini");
    answer = "{\"choices\":[{\"message\":{\"content\":\"\\\"Book a train to Chennai tomorrow\\\"\"}}]}";
    var heard = voice.transcribe(user, wav, "ta-IN", true);
    assertThat(heard.text()).isEqualTo("Book a train to Chennai tomorrow");
    assertThat(heard.provider()).isEqualTo("gemini");
    assertThat(path.get()).isEqualTo("/v1/chat/completions");
    assertThat(auth.get()).isEqualTo("Bearer sk-test");
    JsonNode req = Json.read(body.get());
    assertThat(req.path("model").asText()).isEqualTo(VoiceTranscriber.GEMINI_MODEL);
    JsonNode content = req.path("messages").path(0).path("content");
    assertThat(content.path(0).path("text").asText()).contains("Tamil").contains("English");
    assertThat(content.path(1).path("input_audio").path("format").asText()).isEqualTo("wav");
  }

  @Test
  void openAiTranslatesWithWhisperOrTranscribesAsSpoken() {
    connect(ModelRefs.OPENAI);
    answer = "{\"text\":\"Remind me to pay rent\"}";
    assertThat(voice.transcribe(user, wav, "hi-IN", true).text()).isEqualTo("Remind me to pay rent");
    assertThat(path.get()).isEqualTo("/v1/audio/translations");
    assertThat(body.get()).contains("whisper-1").contains("speech.wav");

    answer = "{\"text\":\"किराया\"}";
    voice.transcribe(user, wav, "hi-IN", false);
    assertThat(path.get()).isEqualTo("/v1/audio/transcriptions");
    assertThat(body.get()).contains(VoiceTranscriber.OPENAI_TRANSCRIBE_MODEL).contains("name=\"language\"");
  }

  @Test
  void aRefusedKeyIsSaidPlainly() {
    connect(ModelRefs.OPENROUTER);
    status = 401;
    answer = "{\"error\":\"bad key\"}";
    assertThatThrownBy(() -> voice.transcribe(user, wav, null, true))
        .isInstanceOf(JizzaException.class).hasMessageContaining("didn't accept your key");
  }

  @Test
  void readsLanguageTags() {
    assertThat(VoiceTranscriber.isoLanguage("auto")).isNull();
    assertThat(VoiceTranscriber.isoLanguage("ml-IN")).isEqualTo("ml");
    assertThat(VoiceTranscriber.languageName("hi-IN")).isEqualTo("Hindi");
    assertThat(VoiceTranscriber.instruction(null, false)).contains("script they spoke");
  }
}

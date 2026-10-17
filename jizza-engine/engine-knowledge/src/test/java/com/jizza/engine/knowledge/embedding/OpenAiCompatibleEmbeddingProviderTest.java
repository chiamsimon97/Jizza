package com.jizza.engine.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.persistence.support.Vectors;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleEmbeddingProviderTest {

  private HttpServer server;
  private final AtomicReference<String> lastBody = new AtomicReference<>();
  private final AtomicReference<String> lastAuth = new AtomicReference<>();
  private volatile int status = 200;
  private volatile String reply = "{\"data\":[{\"embedding\":[3.0,4.0]}]}";

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/embeddings", exchange -> {
      lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] out = reply.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, out.length);
      exchange.getResponseBody().write(out);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private OpenAiCompatibleEmbeddingProvider provider(String apiKey) {
    EmbeddingProperties.OpenAiCompatible config = new EmbeddingProperties.OpenAiCompatible();
    config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
    config.setModel("nomic-embed-text");
    config.setApiKey(apiKey);
    return new OpenAiCompatibleEmbeddingProvider(config);
  }

  @Test
  void fitsTheVectorToTheColumnAndNormalisesIt() {
    float[] v = provider(null).embed("Priya's SIP");

    assertThat(v).hasSize(Vectors.DIMENSIONS);
    assertThat(v[0]).isEqualTo(0.6f);
    assertThat(v[1]).isEqualTo(0.8f);
    assertThat(lastBody.get()).contains("\"model\":\"nomic-embed-text\"").contains("Priya's SIP");
    assertThat(lastAuth.get()).isNull();
  }

  @Test
  void sendsTheKeyOnlyWhenOneIsConfiguredAndNamesTheModelInItsId() {
    var p = provider("sk-test");
    p.embed("x");
    assertThat(lastAuth.get()).isEqualTo("Bearer sk-test");
    assertThat(p.id()).isEqualTo("openai-compatible:nomic-embed-text");
  }

  @Test
  void aFailedCallThrowsRatherThanStoringAZeroVector() {
    status = 500;
    reply = "{}";
    assertThatThrownBy(() -> provider(null).embed("x")).isInstanceOf(IllegalStateException.class);
  }
}

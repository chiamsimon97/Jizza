package com.jizza.engine.knowledge.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.support.Vectors;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Embeddings from any OpenAI-compatible {@code /embeddings} endpoint: a local Ollama or LM Studio,
 * or a hosted service. Output wider than the column is truncated and narrower is zero-padded (see
 * {@link Vectors#fit}), then L2-normalised so cosine distance behaves either way.
 *
 * <p>A failed call throws: callers already degrade to lexical search when a vector is unavailable,
 * and storing a zero vector would silently poison later similarity scores.
 */
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

  private final EmbeddingProperties.OpenAiCompatible config;
  private final HttpClient http;

  public OpenAiCompatibleEmbeddingProvider(EmbeddingProperties.OpenAiCompatible config) {
    this(config, HttpClient.newBuilder().connectTimeout(config.getTimeout()).build());
  }

  OpenAiCompatibleEmbeddingProvider(EmbeddingProperties.OpenAiCompatible config, HttpClient http) {
    this.config = config;
    this.http = http;
  }

  @Override
  public int dimensions() { return Vectors.DIMENSIONS; }

  @Override
  public String id() { return "openai-compatible:" + config.getModel(); }

  @Override
  public float[] embed(String text) {
    if (text == null || text.isBlank()) return new float[dimensions()];
    ObjectNode body = Json.obj();
    body.put("model", config.getModel());
    body.put("input", text);
    if (config.getDimensions() != null) body.put("dimensions", config.getDimensions());

    HttpRequest.Builder request = HttpRequest.newBuilder()
        .uri(URI.create(stripSlash(config.getBaseUrl()) + "/embeddings"))
        .timeout(config.getTimeout())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
    if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
      request.header("Authorization", "Bearer " + config.getApiKey());
    }

    HttpResponse<String> response;
    try {
      response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException("Embedding endpoint unreachable: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while embedding", e);
    }
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("Embedding endpoint returned HTTP " + response.statusCode());
    }
    JsonNode vector = Json.read(response.body()).path("data").path(0).path("embedding");
    if (!vector.isArray() || vector.isEmpty()) {
      throw new IllegalStateException("Embedding endpoint returned no vector");
    }
    float[] raw = new float[vector.size()];
    for (int i = 0; i < raw.length; i++) raw[i] = (float) vector.get(i).asDouble();
    return normalise(Vectors.fit(raw));
  }

  private static float[] normalise(float[] v) {
    double sum = 0;
    for (float f : v) sum += (double) f * f;
    if (sum == 0) return v;
    float norm = (float) Math.sqrt(sum);
    for (int i = 0; i < v.length; i++) v[i] /= norm;
    return v;
  }

  private static String stripSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}

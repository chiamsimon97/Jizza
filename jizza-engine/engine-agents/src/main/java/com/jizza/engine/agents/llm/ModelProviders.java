package com.jizza.engine.agents.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.chat.ChatFiles;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.ModelCatalog;
import com.jizza.engine.api.llm.ModelProviderSettings;
import com.jizza.engine.api.llm.ModelRefs;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.Redaction;
import com.jizza.engine.persistence.entity.LlmProviderEntity;
import com.jizza.engine.persistence.repository.LlmProviderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The AI providers a person connected with their own key, and every model they can pick (F1).
 *
 * <p>Connecting a provider checks the key by listing the provider's models, so a wrong key is
 * caught at once rather than in the middle of a chat. The list is kept with what each model can do
 * (call tools, see photos, read PDFs, how much it reads at once, its price when the provider says),
 * and can be refreshed at any time, so new models show up without a release.
 *
 * <p>The key is stored with the platform's envelope encryption and never returned: the person sees
 * only its last four characters.
 */
@Service
public class ModelProviders implements ModelCatalog, ModelProviderSettings {

  private static final Logger log = LoggerFactory.getLogger(ModelProviders.class);
  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

  private final LlmProviderRepository providers;
  private final EncryptionService encryption;
  private final AgentProperties props;
  private final ObjectProvider<ChatFiles> chatFiles;
  /** One client per connection, made again when the connection changes. */
  private final Map<UUID, CachedClient> clients = new ConcurrentHashMap<>();

  private record CachedClient(Instant version, LlmClient client) {}

  public ModelProviders(LlmProviderRepository providers, EncryptionService encryption,
                        AgentProperties props, ObjectProvider<ChatFiles> chatFiles) {
    this.providers = providers;
    this.encryption = encryption;
    this.props = props;
    this.chatFiles = chatFiles;
  }

  // ------------------------------------------------------------------ views


  public static final List<ProviderKind> KINDS = List.of(
      new ProviderKind(ModelRefs.OPENAI, "OpenAI", "GPT models, with your OpenAI API key.", true,
          false, OpenAiCompatibleLlmClient.defaultBaseUrl(ModelRefs.OPENAI),
          "https://platform.openai.com/api-keys"),
      new ProviderKind(ModelRefs.ANTHROPIC, "Anthropic", "Claude models billed to your own Anthropic account.",
          true, false, "https://api.anthropic.com", "https://console.anthropic.com/settings/keys"),
      new ProviderKind(ModelRefs.GEMINI, "Google Gemini", "Gemini models, with a Google AI Studio key.",
          true, false, OpenAiCompatibleLlmClient.defaultBaseUrl(ModelRefs.GEMINI),
          "https://aistudio.google.com/apikey"),
      new ProviderKind(ModelRefs.OPENROUTER, "OpenRouter", "Hundreds of models from many companies, with one key.",
          true, false, OpenAiCompatibleLlmClient.defaultBaseUrl(ModelRefs.OPENROUTER),
          "https://openrouter.ai/keys"),
      new ProviderKind(ModelRefs.OLLAMA, "Ollama", "Models running on your own computer. Free, and nothing leaves it.",
          false, true, OpenAiCompatibleLlmClient.defaultBaseUrl(ModelRefs.OLLAMA), "https://ollama.com/download"),
      new ProviderKind(ModelRefs.CUSTOM, "Other (OpenAI-compatible)",
          "Any server that speaks the OpenAI chat format, such as LM Studio or vLLM.", false, true, null, null));

  public static ProviderKind kind(String provider) {
    return KINDS.stream().filter(k -> k.provider().equals(provider)).findFirst()
        .orElseThrow(() -> new ValidationException("Unknown AI provider: " + provider));
  }

  @Override
  public List<ProviderKind> kinds() {
    return KINDS;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ProviderView> list(UUID userId) {
    return providers.findByUserIdOrderByCreatedAtAsc(userId).stream().map(this::view).toList();
  }

  private ProviderView view(LlmProviderEntity e) {
    return new ProviderView(e.getId(), e.getProvider(), kind(e.getProvider()).name(), e.getBaseUrl(),
        e.getKeyHint(), e.isEnabled(), e.getModelsRefreshedAt(), e.getLastError(), models(e));
  }

  private static List<ProviderModel> models(LlmProviderEntity e) {
    List<ProviderModel> out = new ArrayList<>();
    if (e.getModels() == null) return out;
    for (JsonNode m : e.getModels()) {
      String id = m.path("id").asText("");
      if (id.isBlank()) continue;
      out.add(new ProviderModel(id, ModelRefs.of(e.getProvider(), id), m.path("label").asText(id),
          m.path("tools").asBoolean(true), m.path("vision").asBoolean(false),
          m.path("pdf").asBoolean(false),
          m.hasNonNull("context") ? m.path("context").asInt() : null,
          decimal(m.path("inputPerMillion")), decimal(m.path("outputPerMillion")),
          m.path("hidden").asBoolean(false)));
    }
    return out;
  }

  private static BigDecimal decimal(JsonNode n) {
    return n == null || n.isMissingNode() || n.isNull() ? null : n.decimalValue();
  }

  // ------------------------------------------------------------------ changes

  /**
   * Connects a provider, or replaces its key and address. The key is checked by listing the
   * provider's models; nothing is saved if that fails.
   *
   * @param apiKey blank keeps the key already saved
   */
  @Override
  @Transactional
  public ProviderView connect(UUID userId, String provider, String baseUrl, String apiKey) {
    ProviderKind kind = kind(provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT));
    LlmProviderEntity entity = providers.findByUserIdAndProvider(userId, kind.provider())
        .orElseGet(() -> {
          LlmProviderEntity created = new LlmProviderEntity();
          created.setUserId(userId);
          created.setProvider(kind.provider());
          return created;
        });
    String address = baseUrl == null || baseUrl.isBlank() ? kind.defaultBaseUrl() : baseUrl.trim();
    if (address == null || address.isBlank()) {
      throw new ValidationException("Give the server's address, such as http://localhost:1234/v1");
    }
    checkAddress(address);
    String key = apiKey == null || apiKey.isBlank()
        ? (entity.getApiKey() == null ? null : encryption.decryptString(entity.getApiKey()))
        : apiKey.trim();
    if (kind.needsKey() && (key == null || key.isBlank())) {
      throw new ValidationException("Paste your " + kind.name() + " API key");
    }

    List<ObjectNode> found = fetchModels(kind.provider(), address, key);
    entity.setBaseUrl(address);
    if (apiKey != null && !apiKey.isBlank()) {
      entity.setApiKey(encryption.encryptString(key));
      entity.setKeyHint(hint(key));
    }
    entity.setModels(merged(entity.getModels(), found));
    entity.setModelsRefreshedAt(Instant.now());
    entity.setLastError(null);
    entity.setEnabled(true);
    LlmProviderEntity saved = providers.save(entity);
    clients.remove(saved.getId());
    return view(saved);
  }

  /** Fetches the provider's model list again; a failure is kept on the connection to show. */
  @Override
  @Transactional
  public ProviderView refresh(UUID userId, String provider) {
    LlmProviderEntity entity = owned(userId, provider);
    String key = entity.getApiKey() == null ? null : encryption.decryptString(entity.getApiKey());
    try {
      entity.setModels(merged(entity.getModels(), fetchModels(entity.getProvider(), entity.getBaseUrl(), key)));
      entity.setModelsRefreshedAt(Instant.now());
      entity.setLastError(null);
    } catch (JizzaException e) {
      entity.setLastError(e.getMessage());
    }
    return view(providers.save(entity));
  }

  @Override
  @Transactional
  public void remove(UUID userId, String provider) {
    LlmProviderEntity entity = owned(userId, provider);
    providers.delete(entity);
    clients.remove(entity.getId());
  }

  @Override
  @Transactional
  public ProviderView setEnabled(UUID userId, String provider, boolean enabled) {
    LlmProviderEntity entity = owned(userId, provider);
    entity.setEnabled(enabled);
    return view(providers.save(entity));
  }


  @Override
  @Transactional
  public ProviderView updateModel(UUID userId, String provider, String modelId, ModelPatch patch) {
    LlmProviderEntity entity = owned(userId, provider);
    boolean found = false;
    for (JsonNode m : entity.getModels()) {
      if (!(m instanceof ObjectNode model) || !modelId.equals(m.path("id").asText())) continue;
      found = true;
      if (patch.hidden() != null) model.put("hidden", patch.hidden());
      if (patch.inputPerMillion() != null) model.put("inputPerMillion", nonNegative(patch.inputPerMillion()));
      if (patch.outputPerMillion() != null) model.put("outputPerMillion", nonNegative(patch.outputPerMillion()));
      if (patch.tools() != null) model.put("tools", patch.tools()).put("set", true);
      if (patch.vision() != null) model.put("vision", patch.vision()).put("set", true);
      if (patch.pdf() != null) model.put("pdf", patch.pdf()).put("set", true);
    }
    if (!found) throw new NotFoundException("Model", modelId);
    entity.setModels(entity.getModels().deepCopy());
    return view(providers.save(entity));
  }

  private static BigDecimal nonNegative(BigDecimal v) {
    if (v.signum() < 0) throw new ValidationException("A price can't be negative");
    return v;
  }

  private LlmProviderEntity owned(UUID userId, String provider) {
    return providers.findByUserIdAndProvider(userId, provider == null ? "" : provider.toLowerCase(Locale.ROOT))
        .orElseThrow(() -> new NotFoundException("AI provider", provider));
  }

  static String hint(String key) {
    String k = key.trim();
    return k.length() <= 4 ? "••••" : "••••" + k.substring(k.length() - 4);
  }

  /** Only web addresses: a local server is fine (Ollama runs on the person's own machine). */
  static void checkAddress(String address) {
    URI uri;
    try {
      uri = URI.create(address);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("That address doesn't look right: " + address);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
      throw new ValidationException("The address must start with http:// or https://");
    }
  }

  /**
   * The fresh list, keeping what the person set on models that are still there: hidden, prices
   * they typed, and what they said a model can do.
   */
  static ArrayNode merged(JsonNode previous, List<ObjectNode> fresh) {
    Map<String, JsonNode> old = new LinkedHashMap<>();
    if (previous != null) previous.forEach(m -> old.put(m.path("id").asText(), m));
    ArrayNode out = Json.mapper().createArrayNode();
    for (ObjectNode m : fresh) {
      JsonNode before = old.get(m.path("id").asText());
      if (before != null) {
        if (before.path("hidden").asBoolean(false)) m.put("hidden", true);
        if (!m.hasNonNull("inputPerMillion") && before.hasNonNull("inputPerMillion")) {
          m.set("inputPerMillion", before.get("inputPerMillion"));
        }
        if (!m.hasNonNull("outputPerMillion") && before.hasNonNull("outputPerMillion")) {
          m.set("outputPerMillion", before.get("outputPerMillion"));
        }
        if (before.path("set").asBoolean(false)) {
          for (String f : List.of("tools", "vision", "pdf")) m.set(f, before.get(f));
          m.put("set", true);
        }
      }
      out.add(m);
    }
    return out;
  }

  // ------------------------------------------------------------------ model lists

  /** The provider's models, chat models only, with what is known about each. */
  List<ObjectNode> fetchModels(String provider, String baseUrl, String key) {
    JsonNode body;
    try {
      RestClient.Builder builder = RestClient.builder()
          .requestFactory(AnthropicLlmClient.requestFactory(java.time.Duration.ofSeconds(20)));
      String url;
      if (ModelRefs.ANTHROPIC.equals(provider)) {
        builder.defaultHeader("x-api-key", key).defaultHeader("anthropic-version",
            props.getAnthropic().getVersion());
        url = OpenAiCompatibleLlmClient.trimSlash(baseUrl) + "/v1/models?limit=100";
      } else {
        if (key != null && !key.isBlank()) builder.defaultHeader("Authorization", "Bearer " + key);
        url = OpenAiCompatibleLlmClient.trimSlash(baseUrl) + "/models";
      }
      body = builder.build().get().uri(URI.create(url)).retrieve().body(JsonNode.class);
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      String why = status == 401 || status == 403
          ? "the key was not accepted" : "it answered " + e.getStatusCode();
      throw new JizzaException(HttpStatus.BAD_REQUEST, "llm-provider-check", "Could not connect",
          kind(provider).name() + " didn't accept the connection: " + why + ". "
              + Redaction.scrub(abbreviate(e.getResponseBodyAsString())));
    } catch (RuntimeException e) {
      throw new JizzaException(HttpStatus.BAD_REQUEST, "llm-provider-check", "Could not connect",
          "Could not reach " + kind(provider).name() + " at " + baseUrl + ": " + e.getMessage());
    }
    List<ObjectNode> out = new ArrayList<>();
    if (body == null) return out;
    for (JsonNode m : body.path("data")) {
      ObjectNode model = describe(provider, m);
      if (model != null) out.add(model);
    }
    // A local server's list is the person's own; a big provider's is sorted so the newest sorts up.
    out.sort(Comparator.comparing((ObjectNode m) -> m.path("label").asText().toLowerCase(Locale.ROOT)));
    return out;
  }

  private static String abbreviate(String s) {
    return s == null ? "" : s.length() <= 300 ? s : s.substring(0, 300) + "…";
  }

  /** Words in a model id that mean it is not a chat model. */
  private static final Pattern NOT_CHAT = Pattern.compile(
      "embed|whisper|tts|dall-e|davinci|babbage|moderation|transcribe|realtime|audio|image|"
          + "search|computer-use|aqa|imagen|veo|-vision-preview|instruct$|codex-mini|sora");

  /** One model of the list, as stored; null for one that is not a chat model. */
  static ObjectNode describe(String provider, JsonNode m) {
    String id = m.path("id").asText("");
    if (ModelRefs.GEMINI.equals(provider) && id.startsWith("models/")) id = id.substring(7);
    if (id.isBlank()) return null;
    String lower = id.toLowerCase(Locale.ROOT);
    ObjectNode out = Json.obj().put("id", id);
    switch (provider) {
      case ModelRefs.OPENAI -> {
        boolean chat = lower.startsWith("gpt-") || lower.startsWith("o1") || lower.startsWith("o3")
            || lower.startsWith("o4") || lower.startsWith("chatgpt-");
        if (!chat || NOT_CHAT.matcher(lower).find()) return null;
        boolean sees = lower.startsWith("gpt-4o") || lower.startsWith("gpt-4.1") || lower.startsWith("gpt-5")
            || lower.startsWith("o3") || lower.startsWith("o4") || lower.equals("o1") || lower.startsWith("chatgpt-");
        out.put("label", openAiLabel(id)).put("tools", !lower.startsWith("chatgpt-"))
            .put("vision", sees).put("pdf", sees);
      }
      case ModelRefs.ANTHROPIC -> out.put("label", m.path("display_name").asText(claudeLabel(id)))
          .put("tools", true).put("vision", true).put("pdf", true).put("context", 200_000);
      case ModelRefs.GEMINI -> {
        if (!lower.startsWith("gemini") || NOT_CHAT.matcher(lower).find()) return null;
        out.put("label", m.path("display_name").asText(geminiLabel(id))).put("tools", true)
            .put("vision", true).put("pdf", true);
      }
      case ModelRefs.OPENROUTER -> {
        JsonNode params = m.path("supported_parameters");
        boolean tools = false;
        for (JsonNode p : params) if ("tools".equals(p.asText())) tools = true;
        boolean vision = false;
        boolean pdf = false;
        for (JsonNode mod : m.path("architecture").path("input_modalities")) {
          if ("image".equals(mod.asText())) vision = true;
          if ("file".equals(mod.asText())) pdf = true;
        }
        JsonNode outputs = m.path("architecture").path("output_modalities");
        if (outputs.isArray() && !outputs.isEmpty()) {
          boolean text = false;
          for (JsonNode o : outputs) if ("text".equals(o.asText())) text = true;
          if (!text) return null;
        }
        out.put("label", m.path("name").asText(id)).put("tools", tools).put("vision", vision)
            .put("pdf", pdf);
        if (m.hasNonNull("context_length")) out.put("context", m.path("context_length").asInt());
        BigDecimal in = perMillion(m.path("pricing").path("prompt"));
        BigDecimal outPrice = perMillion(m.path("pricing").path("completion"));
        if (in != null) out.put("inputPerMillion", in);
        if (outPrice != null) out.put("outputPerMillion", outPrice);
      }
      default -> {
        // A local or other server: whatever it lists is the person's own choice.
        if (NOT_CHAT.matcher(lower).find() && !lower.contains("instruct")) return null;
        out.put("label", id).put("tools", true).put("vision", lower.contains("llava")
            || lower.contains("vision") || lower.contains("-vl")).put("pdf", false);
      }
    }
    return out;
  }

  /** OpenRouter gives a price per token as text; zero is a free model. */
  private static BigDecimal perMillion(JsonNode perToken) {
    if (perToken == null || perToken.isMissingNode() || perToken.isNull()) return null;
    try {
      BigDecimal v = new BigDecimal(perToken.asText().trim());
      if (v.signum() < 0) return null;
      return v.multiply(MILLION).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static String openAiLabel(String id) {
    String s = id.replaceFirst("^gpt-", "GPT-").replaceFirst("^chatgpt-", "ChatGPT ");
    return s.replace("-mini", " mini").replace("-nano", " nano").replace("-pro", " Pro")
        .replace("-turbo", " Turbo");
  }

  /** {@code claude-sonnet-5-5} reads "Claude Sonnet 5.5". */
  public static String claudeLabel(String id) {
    String[] parts = id.split("-");
    StringBuilder out = new StringBuilder();
    List<String> numbers = new ArrayList<>();
    for (String p : parts) {
      if (p.matches("\\d{8}")) continue; // a date suffix
      if (p.matches("\\d+")) {
        numbers.add(p);
        continue;
      }
      if (!out.isEmpty()) out.append(' ');
      out.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
    }
    if (!numbers.isEmpty()) out.append(' ').append(String.join(".", numbers));
    return out.toString();
  }

  static String geminiLabel(String id) {
    String s = id.replace('-', ' ');
    StringBuilder out = new StringBuilder();
    for (String w : s.split(" ")) {
      if (w.isEmpty()) continue;
      if (!out.isEmpty()) out.append(' ');
      out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
    }
    return out.toString();
  }

  // ------------------------------------------------------------------ catalog

  /** True when the platform's own Anthropic key is set, so its models can be offered. */
  boolean platformAvailable() {
    String key = props.getAnthropic().getApiKey();
    return key != null && !key.isBlank();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ModelInfo> available(UUID userId) {
    List<ModelInfo> out = new ArrayList<>(platformModels());
    if (userId == null) return out;
    for (LlmProviderEntity e : providers.findByUserIdOrderByCreatedAtAsc(userId)) {
      if (!e.isEnabled()) continue;
      for (ProviderModel m : models(e)) {
        if (!m.hidden()) out.add(info(e.getProvider(), m));
      }
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ModelInfo> find(UUID userId, String ref) {
    if (ref == null || ref.isBlank()) return Optional.empty();
    var parsed = ModelRefs.parse(ref);
    if (parsed.isEmpty()) {
      return Optional.of(platformModels().stream().filter(m -> m.ref().equals(ref)).findFirst()
          .orElseGet(() -> platformInfo(ref, null)));
    }
    if (userId == null) return Optional.empty();
    return providers.findByUserIdAndProvider(userId, parsed.get().provider())
        .flatMap(e -> models(e).stream().filter(m -> m.id().equals(parsed.get().modelId()))
            .findFirst().map(m -> info(e.getProvider(), m)));
  }

  private static ModelInfo info(String provider, ProviderModel m) {
    return new ModelInfo(m.ref(), provider, m.label(), "Your " + kind(provider).name() + " key",
        true, m.tools(), m.vision(), m.pdf(), m.contextTokens(), m.inputPerMillion(),
        m.outputPerMillion());
  }

  /** The platform's three tiers, each with a plain note on when to pick it. */
  List<ModelInfo> platformModels() {
    if (!platformAvailable()) return List.of();
    Map<String, ModelInfo> out = new LinkedHashMap<>();
    for (ModelTier tier : List.of(ModelTier.ROUTING, ModelTier.STANDARD, ModelTier.DEEP)) {
      String model = props.getModels().get(tier);
      if (model == null || model.isBlank() || out.containsKey(model)) continue;
      String note = switch (tier) {
        case ROUTING -> "Fast and cheap";
        case DEEP -> "Best for hard problems";
        default -> "Good for most things";
      };
      out.put(model, platformInfo(model, note));
    }
    return List.copyOf(out.values());
  }

  private ModelInfo platformInfo(String model, String note) {
    AgentProperties.ModelPrice price = props.getPricing().get(model);
    return new ModelInfo(model, ModelRefs.ANTHROPIC, claudeLabel(model), note, false, true, true,
        true, 200_000,
        price == null ? null : BigDecimal.valueOf(price.getInputPerMillion()),
        price == null ? null : BigDecimal.valueOf(price.getOutputPerMillion()));
  }

  // ------------------------------------------------------------------ clients

  /** The client for one of the person's connections, or why it can't be used. */
  LlmClient clientFor(UUID userId, String provider) {
    LlmProviderEntity entity = providers.findByUserIdAndProvider(userId, provider)
        .orElseThrow(() -> new JizzaException(HttpStatus.BAD_REQUEST, "llm-provider-missing",
            "Model not available", kind(provider).name() + " isn't connected any more. Connect it "
                + "again under Settings, AI models, or pick another model."));
    if (!entity.isEnabled()) {
      throw new JizzaException(HttpStatus.BAD_REQUEST, "llm-provider-off", "Model not available",
          kind(provider).name() + " is switched off under Settings, AI models.");
    }
    CachedClient cached = clients.get(entity.getId());
    if (cached != null && cached.version().equals(entity.getUpdatedAt())) return cached.client();
    String key = entity.getApiKey() == null ? null : encryption.decryptString(entity.getApiKey());
    Map<String, ModelInfo> known = new LinkedHashMap<>();
    models(entity).forEach(m -> known.put(m.id(), info(entity.getProvider(), m)));
    LlmClient client = ModelRefs.ANTHROPIC.equals(provider)
        ? new AnthropicLlmClient(withBaseUrl(entity.getBaseUrl()), chatFiles::getIfAvailable, key)
        : new OpenAiCompatibleLlmClient(provider, entity.getBaseUrl(), key, props,
            chatFiles::getIfAvailable, known::get);
    clients.put(entity.getId(), new CachedClient(entity.getUpdatedAt(), client));
    log.debug("Made a model client for {} ({})", provider, entity.getId());
    return client;
  }

  /** The platform's settings with another Anthropic address (a person's own proxy, rarely). */
  private AgentProperties withBaseUrl(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()
        || OpenAiCompatibleLlmClient.trimSlash(baseUrl).equals(
            OpenAiCompatibleLlmClient.trimSlash(props.getAnthropic().getBaseUrl()))) {
      return props;
    }
    AgentProperties copy = new AgentProperties();
    copy.setModels(props.getModels());
    copy.setPricing(props.getPricing());
    copy.getAnthropic().setBaseUrl(baseUrl);
    copy.getAnthropic().setVersion(props.getAnthropic().getVersion());
    copy.getAnthropic().setTimeout(props.getAnthropic().getTimeout());
    copy.getAnthropic().setMaxOutputTokens(props.getAnthropic().getMaxOutputTokens());
    copy.getAnthropic().setEffort(props.getAnthropic().getEffort());
    copy.getAnthropic().setEffortModels(props.getAnthropic().getEffortModels());
    return copy;
  }
}

package com.jizza.engine.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.llm.ModelRefs;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.LlmProviderEntity;
import com.jizza.engine.persistence.repository.LlmProviderRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ModelProvidersTest {

  private LlmProviderRepository repo;
  private AgentProperties props;
  private ModelProviders providers;
  private final UUID user = UUID.randomUUID();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    repo = mock(LlmProviderRepository.class);
    props = new AgentProperties();
    props.getAnthropic().setApiKey("platform-key");
    props.getModels().put(ModelTier.ROUTING, "claude-haiku-5-5");
    props.getModels().put(ModelTier.STANDARD, "claude-sonnet-5-5");
    props.getModels().put(ModelTier.DEEP, "claude-opus-5-5");
    providers = new ModelProviders(repo, mock(EncryptionService.class), props, mock(ObjectProvider.class));
  }

  @Test
  void readsModelReferences() {
    assertThat(ModelRefs.parse("claude-sonnet-5-5")).isEmpty();
    assertThat(ModelRefs.parse("openai:gpt-5.1")).contains(new ModelRefs.Ref("openai", "gpt-5.1"));
    // Local model ids carry colons of their own; only the first one separates.
    assertThat(ModelRefs.parse("ollama:llama3.3:70b")).contains(new ModelRefs.Ref("ollama", "llama3.3:70b"));
    assertThat(ModelRefs.parse("nobody:thing")).isEmpty();
    assertThat(ModelRefs.ownKey("openrouter:meta-llama/llama-4")).isTrue();
  }

  @Test
  void keepsOnlyChatModelsFromOpenAisList() {
    assertThat(ModelProviders.describe("openai", Json.obj().put("id", "gpt-5.1"))).isNotNull();
    assertThat(ModelProviders.describe("openai", Json.obj().put("id", "text-embedding-3-large"))).isNull();
    assertThat(ModelProviders.describe("openai", Json.obj().put("id", "gpt-4o-mini-tts"))).isNull();
    assertThat(ModelProviders.describe("openai", Json.obj().put("id", "whisper-1"))).isNull();
    ObjectNode mini = ModelProviders.describe("openai", Json.obj().put("id", "gpt-5-mini"));
    assertThat(mini.path("label").asText()).isEqualTo("GPT-5 mini");
    assertThat(mini.path("vision").asBoolean()).isTrue();
  }

  @Test
  void readsWhatOpenRouterSaysAModelCanDoAndCosts() {
    ObjectNode m = (ObjectNode) Json.read("""
        {"id":"meta-llama/llama-4","name":"Meta: Llama 4","context_length":131072,
         "pricing":{"prompt":"0.00000018","completion":"0.0000006"},
         "architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},
         "supported_parameters":["tools","temperature"]}""");
    ObjectNode out = ModelProviders.describe("openrouter", m);
    assertThat(out.path("tools").asBoolean()).isTrue();
    assertThat(out.path("vision").asBoolean()).isTrue();
    assertThat(out.path("pdf").asBoolean()).isFalse();
    assertThat(out.path("context").asInt()).isEqualTo(131072);
    assertThat(out.path("inputPerMillion").decimalValue()).isEqualByComparingTo("0.18");
    assertThat(out.path("outputPerMillion").decimalValue()).isEqualByComparingTo("0.6");
  }

  @Test
  void dropsGeminisPrefixAndItsNonChatModels() {
    assertThat(ModelProviders.describe("gemini", Json.obj().put("id", "models/gemini-2.5-pro"))
        .path("id").asText()).isEqualTo("gemini-2.5-pro");
    assertThat(ModelProviders.describe("gemini", Json.obj().put("id", "models/text-embedding-004"))).isNull();
  }

  @Test
  void aRefreshKeepsWhatThePersonSet() {
    var before = Json.mapper().createArrayNode();
    before.add(Json.obj().put("id", "a").put("hidden", true).put("inputPerMillion", 1.5));
    before.add(Json.obj().put("id", "b").put("tools", false).put("set", true));
    var merged = ModelProviders.merged(before, List.of(
        Json.obj().put("id", "a").put("tools", true), Json.obj().put("id", "b").put("tools", true),
        Json.obj().put("id", "c")));
    assertThat(merged).hasSize(3);
    assertThat(merged.get(0).path("hidden").asBoolean()).isTrue();
    assertThat(merged.get(0).path("inputPerMillion").asDouble()).isEqualTo(1.5);
    assertThat(merged.get(1).path("tools").asBoolean()).isFalse();
  }

  @Test
  void namesClaudeModelsPlainly() {
    assertThat(ModelProviders.claudeLabel("claude-sonnet-5-5")).isEqualTo("Claude Sonnet 5.5");
    assertThat(ModelProviders.claudeLabel("claude-haiku-4-5-20251001")).isEqualTo("Claude Haiku 4.5");
  }

  @Test
  void offersThePlatformTiersThenThePersonsOwnModels() {
    LlmProviderEntity openai = new LlmProviderEntity();
    openai.setUserId(user);
    openai.setProvider("openai");
    var models = Json.mapper().createArrayNode();
    models.add(Json.obj().put("id", "gpt-5.1").put("label", "GPT-5.1").put("tools", true));
    models.add(Json.obj().put("id", "gpt-4o").put("label", "GPT-4o").put("hidden", true));
    openai.setModels(models);
    when(repo.findByUserIdOrderByCreatedAtAsc(user)).thenReturn(List.of(openai));
    when(repo.findByUserIdAndProvider(user, "openai")).thenReturn(Optional.of(openai));

    var available = providers.available(user);
    assertThat(available).extracting(m -> m.ref())
        .containsExactly("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "openai:gpt-5.1");
    assertThat(available.get(0).note()).isEqualTo("Fast and cheap");
    assertThat(available.get(3).ownKey()).isTrue();
    assertThat(providers.find(user, "openai:gpt-5.1")).isPresent();
    assertThat(providers.find(user, "openai:nope")).isEmpty();
    assertThat(providers.find(UUID.randomUUID(), "anthropic:claude-x")).isEmpty();
  }

  @Test
  void refusesAnAddressThatIsNotAWebAddress() {
    assertThatThrownBy(() -> ModelProviders.checkAddress("file:///etc/passwd"))
        .isInstanceOf(ValidationException.class);
    ModelProviders.checkAddress("http://localhost:11434/v1");
  }

  @Test
  void showsOnlyTheEndOfAKey() {
    assertThat(ModelProviders.hint("sk-abcdefgh1234")).isEqualTo("••••1234");
  }

  @Test
  void aProviderThatIsNotConnectedSaysSoPlainly() {
    when(repo.findByUserIdAndProvider(any(), any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> providers.clientFor(user, "openai"))
        .hasMessageContaining("OpenAI isn't connected");
  }
}

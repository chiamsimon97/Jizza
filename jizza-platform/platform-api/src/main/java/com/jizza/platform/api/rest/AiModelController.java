package com.jizza.platform.api.rest;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.llm.ModelCatalog;
import com.jizza.engine.api.llm.ModelProviderSettings;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.StepUpRequired;
import com.jizza.engine.security.session.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A choice of models (F1): the AI providers a person connects with their own key, and every model
 * they can pick in the chat box or for an agent. Keys are taken here and never handed back.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/ai-models")
public class AiModelController {

  private final ModelProviderSettings providers;
  private final ModelCatalog catalog;
  private final AuditLog audit;

  public AiModelController(ModelProviderSettings providers, ModelCatalog catalog, AuditLog audit) {
    this.providers = providers;
    this.catalog = catalog;
    this.audit = audit;
  }

  /** What the settings page and the model picker need in one read. */
  public record Overview(List<ModelProviderSettings.ProviderKind> kinds,
                         List<ModelProviderSettings.ProviderView> connected,
                         List<ModelCatalog.ModelInfo> models) {}

  @GetMapping
  public Overview overview() {
    UUID userId = CurrentUser.userId();
    return new Overview(providers.kinds(), providers.list(userId), catalog.available(userId));
  }

  /** Just the models to pick from. */
  @GetMapping("/models")
  public List<ModelCatalog.ModelInfo> models() {
    return catalog.available(CurrentUser.userId());
  }

  public record ConnectBody(@Size(max = 500) String baseUrl, @Size(max = 500) String apiKey) {}

  /** Saving a key that spends money is a sensitive change, so it needs a fresh re-authentication. */
  @PutMapping("/providers/{provider}")
  @StepUpRequired
  public ModelProviderSettings.ProviderView connect(@PathVariable String provider,
                                                    @Valid @RequestBody ConnectBody body) {
    UUID userId = CurrentUser.userId();
    var view = providers.connect(userId, provider, body.baseUrl(), body.apiKey());
    audit.record(userId, ActorType.USER, userId, "ai.provider.connected", view.provider(),
        Json.obj().put("models", view.models().size())
            .put("keyChanged", body.apiKey() != null && !body.apiKey().isBlank()));
    return view;
  }

  @PostMapping("/providers/{provider}/refresh")
  public ModelProviderSettings.ProviderView refresh(@PathVariable String provider) {
    return providers.refresh(CurrentUser.userId(), provider);
  }

  public record EnabledBody(boolean enabled) {}

  @PatchMapping("/providers/{provider}")
  public ModelProviderSettings.ProviderView setEnabled(@PathVariable String provider,
                                                       @RequestBody EnabledBody body) {
    UUID userId = CurrentUser.userId();
    var view = providers.setEnabled(userId, provider, body.enabled());
    audit.record(userId, ActorType.USER, userId, body.enabled() ? "ai.provider.on" : "ai.provider.off",
        view.provider(), null);
    return view;
  }

  @DeleteMapping("/providers/{provider}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(@PathVariable String provider) {
    UUID userId = CurrentUser.userId();
    providers.remove(userId, provider);
    audit.record(userId, ActorType.USER, userId, "ai.provider.removed", provider, null);
  }

  /** One model's settings; its id goes in the query, since ids carry slashes and colons. */
  @PatchMapping("/providers/{provider}/model")
  public ModelProviderSettings.ProviderView updateModel(@PathVariable String provider,
                                                        @RequestParam String id,
                                                        @RequestBody ModelProviderSettings.ModelPatch body) {
    return providers.updateModel(CurrentUser.userId(), provider, id, body);
  }
}

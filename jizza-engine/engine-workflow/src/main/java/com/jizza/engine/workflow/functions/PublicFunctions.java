package com.jizza.engine.workflow.functions;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.functions.ScriptFunctionDraft;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Jizza's public functions: ready-made ones every person sees under Functions and calls as
 * {@code fn.<name>(…)}, kept in {@code functions/public-functions.json}. Each startup brings the
 * stored copies in line with that file, adding new ones and updating changed ones; one taken out of
 * the file stays stored, since workflows may still run it.
 *
 * <p>Each entry in the file also lists examples ({@code args} and what it {@code returns}), which
 * the tests run, so a public function can't ship broken.
 */
@Component
public class PublicFunctions {

  static final String RESOURCE = "functions/public-functions.json";

  private static final Logger log = LoggerFactory.getLogger(PublicFunctions.class);

  private final StoredScriptFunctions functions;

  public PublicFunctions(StoredScriptFunctions functions) {
    this.functions = functions;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void sync() {
    int changed = 0;
    for (ScriptFunctionDraft draft : drafts()) {
      try {
        if (functions.savePublic(draft)) changed++;
      } catch (RuntimeException e) {
        log.error("The public function {} could not be saved: {}", draft.name(), e.getMessage());
      }
    }
    if (changed > 0) log.info("Saved {} public function(s)", changed);
  }

  /** The file's entries, as drafts. */
  public static List<ScriptFunctionDraft> drafts() {
    List<ScriptFunctionDraft> out = new ArrayList<>();
    for (JsonNode f : entries()) {
      out.add(new ScriptFunctionDraft(f.path("name").asText(), f.path("description").asText(null),
          f.path("inputs"), f.path("script").asText(), f.path("returnType").asText("any"), f.path("returnFields")));
    }
    return out;
  }

  /** The file as it is, examples included. */
  static JsonNode entries() {
    try (InputStream in = PublicFunctions.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) return Json.mapper().createArrayNode();
      return Json.mapper().readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

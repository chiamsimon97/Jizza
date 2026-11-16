package com.jizza.engine.workflow.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.library.ReuseProfile;
import com.jizza.engine.api.workflow.library.WorkflowIntents;
import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Suggests a workflow's summary and example requests with one cheap-model call. */
@Service
public class DefaultWorkflowIntents implements WorkflowIntents {

  private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowIntents.class);
  private static final int EXAMPLES = 5;

  static final String PROMPT = """
      A person saved a workflow that does a job for them, often by working a website in their
      browser. Say what it is for and how they would ask for it, so a later request in their own
      words finds it. Answer with one JSON object and nothing else:
      {"summary": "<one sentence: what it does, naming the site or service>",
       "examples": ["<request>", ...]}
      Give 3 to 5 examples, each a short request the person might type, in plain everyday words,
      varied (with and without the site's name, with and without details such as places or
      dates). Use only what the name, steps and sites show; never invent a feature it lacks.""";

  private final WorkflowRepository workflows;
  private final ObjectProvider<WorkflowLibrary> library;
  private final ObjectProvider<LlmClient> llm;
  private final ObjectProvider<CostAccountant> costs;
  private final ExecutorService background =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jizza-intents-", 0).factory());

  public DefaultWorkflowIntents(WorkflowRepository workflows, ObjectProvider<WorkflowLibrary> library,
                                ObjectProvider<LlmClient> llm, ObjectProvider<CostAccountant> costs) {
    this.workflows = workflows;
    this.library = library;
    this.llm = llm;
    this.costs = costs;
  }

  @Override
  public WorkflowLibrary.Match suggest(UUID userId, UUID workflowId) {
    WorkflowEntity workflow = workflows.findById(workflowId)
        .filter(w -> userId.equals(w.getUserId()))
        .orElseThrow(() -> new NotFoundException("Workflow", workflowId));
    LlmClient client = llm.getIfAvailable();
    WorkflowLibrary lib = library.getIfAvailable();
    if (client == null || lib == null) throw new ValidationException("Suggestions are not available here");
    String model = client.modelFor(ModelTier.ROUTING);
    LlmResponse response = client.complete(model,
        List.of(Msg.system(PROMPT), Msg.user(describe(workflow))), List.of());
    CostAccountant accountant = costs.getIfAvailable();
    if (accountant != null) accountant.record(userId, model, response, "workflow:intents");
    Suggestion s = parse(response.text());
    if (s == null) throw new ValidationException("Jizza could not suggest how you'd ask for this one. Try again");
    ReuseProfile current = DefaultWorkflowLibrary.profileFor(workflow, WorkflowLibrary.Use.CHAT);
    ReuseProfile saved = ReuseProfile.from(workflow.getReuse());
    ReuseProfile next = new ReuseProfile(s.summary(), s.examples(), current.inputs(),
        saved == null ? ReuseProfile.Guardrails.DEFAULT : saved.guardrails(),
        saved == null ? null : saved.learnedFrom());
    return lib.describe(userId, workflowId, next);
  }

  @Override
  public void suggestLater(UUID userId, UUID workflowId) {
    Runnable job = () -> background.submit(() -> {
      try {
        WorkflowEntity w = workflows.findById(workflowId).orElse(null);
        ReuseProfile p = w == null ? null : ReuseProfile.from(w.getReuse());
        if (w == null || p != null && !p.examples().isEmpty()) return;
        suggest(userId, workflowId);
      } catch (RuntimeException e) {
        log.info("No example requests suggested for workflow {}: {}", workflowId, e.getMessage());
      }
    });
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          job.run();
        }
      });
    } else {
      job.run();
    }
  }

  /** The name, description and what the steps say (labels, sites, inputs), never typed values. */
  static String describe(WorkflowEntity workflow) {
    StringBuilder text = new StringBuilder("Name: ").append(workflow.getName()).append('\n');
    if (workflow.getDescription() != null && !workflow.getDescription().isBlank()) {
      text.append("Description: ").append(workflow.getDescription()).append('\n');
    }
    String steps = DefaultWorkflowLibrary.stepsText(workflow.getGraph());
    if (steps != null && !steps.isBlank()) {
      String flat = steps.replaceAll("\\s+", " ").strip();
      text.append("Steps and sites: ").append(flat, 0, Math.min(flat.length(), 2500)).append('\n');
    }
    List<String> inputs = DefaultWorkflowLibrary.declaredInputs(workflow).stream()
        .map(ReuseProfile.Input::name).toList();
    if (!inputs.isEmpty()) text.append("It is told: ").append(String.join(", ", inputs)).append('\n');
    return text.toString();
  }

  record Suggestion(String summary, List<String> examples) {}

  static Suggestion parse(String answer) {
    if (answer == null) return null;
    int start = answer.indexOf('{');
    int end = answer.lastIndexOf('}');
    if (start < 0 || end <= start) return null;
    try {
      JsonNode node = Json.mapper().readTree(answer.substring(start, end + 1));
      String summary = node.path("summary").asText("").strip();
      List<String> examples = new ArrayList<>();
      node.path("examples").forEach(e -> {
        String t = e.asText("").strip();
        if (!t.isEmpty() && t.length() <= 200 && examples.size() < EXAMPLES) examples.add(t);
      });
      if (summary.isEmpty() || examples.isEmpty()) return null;
      return new Suggestion(summary.length() > 300 ? summary.substring(0, 300) : summary, examples);
    } catch (Exception e) {
      return null;
    }
  }

  @jakarta.annotation.PreDestroy
  void shutdown() {
    background.shutdown();
  }
}

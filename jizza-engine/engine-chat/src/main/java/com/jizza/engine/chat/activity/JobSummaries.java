package com.jizza.engine.chat.activity;

import com.jizza.engine.api.activity.ActivityTypes;
import com.jizza.engine.api.chat.JobEventDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The one line a finished job collapses to in the Activity panel, written from its own timeline so
 * it is exact, free and instant. "Asked Kai. Kai called Zerodha (GET /portfolio/holdings). Used 4
 * memories."
 */
public final class JobSummaries {

  private JobSummaries() {}

  public static String summarize(List<JobEventDto> events) {
    Set<String> asked = new LinkedHashSet<>();
    Set<String> calls = new LinkedHashSet<>();
    Set<String> approvals = new LinkedHashSet<>();
    int memories = 0;
    int otherTools = 0;
    Set<String> pages = new LinkedHashSet<>();
    String lastToolAgent = null;
    String outcome = null;

    for (JobEventDto e : events) {
      switch (e.type()) {
        case ActivityTypes.AGENT_MESSAGE -> {
          if ("task".equals(e.payload().path("kind").asText())) {
            asked.add(e.payload().path("toAgentName").asText());
          }
        }
        case ActivityTypes.TOOL_CALL -> {
          String state = e.payload().path("state").asText();
          if ("started".equals(state)) lastToolAgent = e.agentName();
          if ("done".equals(state) && !e.payload().path("tool").asText().startsWith("memory_")) {
            otherTools++;
          }
        }
        case ActivityTypes.TOOL_API -> {
          String who = lastToolAgent == null ? "" : lastToolAgent + " called ";
          calls.add(who + e.payload().path("service").asText() + " ("
              + e.payload().path("method").asText() + " " + e.payload().path("path").asText() + ")");
          otherTools--;
        }
        case ActivityTypes.MEMORY_USED -> memories += e.payload().path("count").asInt();
        case ActivityTypes.WEB_SOURCES -> e.payload().path("sources")
            .forEach(source -> pages.add(source.path("url").asText()));
        case ActivityTypes.APPROVAL_REQUIRED -> approvals.add(e.payload().path("label").asText());
        case ActivityTypes.JOB_FAILED -> outcome = "Stopped: " + e.payload().path("detail").asText();
        case ActivityTypes.JOB_CANCELLED -> outcome = "Stopped by you.";
        default -> { }
      }
    }

    List<String> parts = new ArrayList<>();
    if (!asked.isEmpty()) parts.add("Asked " + join(new ArrayList<>(asked)) + ".");
    if (!calls.isEmpty()) parts.add(capitalize(join(new ArrayList<>(calls))) + ".");
    if (otherTools > 0) parts.add("Used " + otherTools + (otherTools == 1 ? " tool." : " tools."));
    if (!pages.isEmpty()) {
      parts.add("Looked at " + pages.size() + (pages.size() == 1 ? " web page." : " web pages."));
    }
    if (memories > 0) parts.add("Used " + memories + (memories == 1 ? " memory." : " memories."));
    if (!approvals.isEmpty()) parts.add("Waiting for your approval: " + join(new ArrayList<>(approvals)) + ".");
    if (outcome != null) parts.add(outcome);
    if (parts.isEmpty()) return "Answered directly.";
    return String.join(" ", parts);
  }

  /** The job's title: what the user asked, on one line. */
  public static String title(String userText) {
    if (userText == null) return null;
    String oneLine = userText.replaceAll("\\s+", " ").trim();
    return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 79) + "…";
  }

  static String join(List<String> items) {
    if (items.size() == 1) return items.get(0);
    return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
  }

  private static String capitalize(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}

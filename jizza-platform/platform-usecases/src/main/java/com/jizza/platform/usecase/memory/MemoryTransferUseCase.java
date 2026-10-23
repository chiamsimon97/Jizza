package com.jizza.platform.usecase.memory;

import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.DataClassification;
import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.enums.MemorySource;
import com.jizza.engine.api.enums.MemoryStatus;
import com.jizza.engine.api.enums.MemoryWriteOutcome;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.knowledge.KnowledgeEntry;
import com.jizza.engine.api.knowledge.KnowledgeStore;
import com.jizza.engine.api.memory.Actor;
import com.jizza.engine.api.memory.Memory;
import com.jizza.engine.api.memory.MemoryDraft;
import com.jizza.engine.api.memory.MemoryFilter;
import com.jizza.engine.api.memory.MemoryScope;
import com.jizza.engine.api.memory.MemoryStore;
import com.jizza.engine.api.memory.MemorySubject;
import com.jizza.engine.api.memory.MemoryWrite;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

/**
 * Taking memory out of Jizza and bringing it in from elsewhere, like the memory settings of other
 * assistants.
 *
 * <p>Export is a zip of Markdown files: the user's facts by category, the people and things they
 * are about, and each agent's own notes. Restricted memories stay out of it, and so does text read
 * from files (the files themselves are the user's already).
 *
 * <p>Import takes what another assistant remembers, pasted or uploaded, or a Jizza export. It is
 * only ever data: each line becomes one fact, sourced {@code IMPORT} and left unconfirmed for the
 * user to review. Nothing imported becomes a standing instruction, whatever its heading says.
 */
@Service
public class MemoryTransferUseCase {

  static final int MAX_IMPORT_CHARS = 200_000;
  static final int MAX_IMPORT_FACTS = 500;
  static final int MAX_ZIP_BYTES = 5 * 1024 * 1024;
  private static final int MAX_ZIP_ENTRIES = 200;
  private static final int MAX_FACT_CHARS = 2000;
  private static final int MAX_NOTE_CHARS = 20_000;
  private static final int EXPORT_LIMIT = 1000;
  private static final double IMPORTED_CONFIDENCE = 0.7;

  /**
   * What to paste into another assistant so it writes out what it remembers in a shape Jizza
   * reads back: one fact per line under a heading per category.
   */
  public static final String IMPORT_PROMPT = """
      I'm moving to another assistant and want to take what you remember about me with me.
      Please list everything you have stored or learned about me: my memories, saved details and \
      preferences, and anything else useful you know about me from our past conversations.

      Write it in Markdown, under these headings, leaving out any heading with nothing under it:
      ## Profile
      ## Preferences
      ## People and relationships
      ## Work
      ## Projects
      ## Tasks
      ## Finance
      ## Health
      ## Other

      Put one fact per line, starting with "- ", in plain words and as complete as you can \
      (for example "- I prefer short replies with the answer first."). Don't add commentary, \
      summaries or anything you are unsure of, and put the whole list in one code block so I can \
      copy it.""";

  /** How an import went, for the user to see. */
  public record ImportResult(int created, int duplicates, int pending, int notes, int skipped,
                             List<String> problems) {

    public int total() { return created + duplicates + pending + notes + skipped; }
  }

  /** The export itself. */
  public record Export(String filename, byte[] zip) {}

  private final MemoryStore store;
  private final KnowledgeStore knowledge;
  private final AgentDirectory agents;
  private final Clock clock;

  public MemoryTransferUseCase(MemoryStore store, KnowledgeStore knowledge, AgentDirectory agents) {
    this(store, knowledge, agents, Clock.systemUTC());
  }

  MemoryTransferUseCase(MemoryStore store, KnowledgeStore knowledge, AgentDirectory agents,
                        Clock clock) {
    this.store = store;
    this.knowledge = knowledge;
    this.agents = agents;
    this.clock = clock;
  }

  public String importPrompt() {
    return IMPORT_PROMPT;
  }

  // ── Export ──────────────────────────────────────────────────────────────────────────────────

  public Export export(UUID userId) {
    Instant now = clock.instant();
    List<Memory> facts = store.list(userId, MemoryFilter.all(EXPORT_LIMIT)).stream()
        .filter(m -> m.status() == MemoryStatus.ACTIVE || m.status() == MemoryStatus.PENDING)
        .toList();
    long withheld = facts.stream().filter(Memory::restricted).count();
    List<MemorySubject> subjects = store.subjects(userId, MemoryScope.everything());
    Map<UUID, String> subjectNames = subjects.stream()
        .collect(Collectors.toMap(MemorySubject::id, MemorySubject::name, (a, b) -> a));

    Map<MemoryCategory, List<Memory>> byCategory = new EnumMap<>(MemoryCategory.class);
    for (Memory m : facts) {
      if (m.restricted() || m.content() == null) continue;
      byCategory.computeIfAbsent(m.category(), c -> new ArrayList<>()).add(m);
    }

    Map<String, String> files = new LinkedHashMap<>();
    int exportedFacts = 0;
    for (var e : byCategory.entrySet()) {
      StringBuilder md = new StringBuilder("# ").append(heading(e.getKey())).append("\n\n");
      for (Memory m : e.getValue()) {
        md.append("- ").append(oneLine(m.content()));
        String about = m.subjectIds() == null ? "" : m.subjectIds().stream()
            .map(subjectNames::get).filter(n -> n != null).collect(Collectors.joining(", "));
        List<String> tags = new ArrayList<>();
        if (!about.isEmpty()) tags.add("about " + about);
        if (!m.confirmed()) tags.add("unconfirmed");
        if (m.status() == MemoryStatus.PENDING) tags.add("waiting for review");
        if (!tags.isEmpty()) md.append(" _(").append(String.join("; ", tags)).append(")_");
        md.append('\n');
        exportedFacts++;
      }
      files.put("facts/" + e.getKey().name().toLowerCase(Locale.ROOT) + ".md", md.toString());
    }

    if (!subjects.isEmpty()) {
      StringBuilder md = new StringBuilder("# People and things\n\n");
      for (MemorySubject s : subjects) {
        md.append("- **").append(oneLine(s.name())).append("**");
        if (s.kind() != null) md.append(" · ").append(s.kind().name().toLowerCase(Locale.ROOT));
        if (s.relation() != null && !s.relation().isBlank()) md.append(" · ").append(oneLine(s.relation()));
        md.append('\n');
      }
      files.put("people-and-things.md", md.toString());
    }

    int exportedNotes = 0;
    Set<String> usedNames = new HashSet<>();
    for (AgentDto agent : agents.list(userId)) {
      List<KnowledgeEntry> notes = knowledge.list(agent.id(), EXPORT_LIMIT).stream()
          .filter(n -> !n.restricted() && !fromFile(n))
          .toList();
      if (notes.isEmpty()) continue;
      StringBuilder md = new StringBuilder("# ").append(oneLine(agent.name())).append("\n\n");
      for (KnowledgeEntry n : notes) {
        md.append("## ").append(oneLine(n.title() == null || n.title().isBlank() ? "Note" : n.title()))
            .append("\n\n").append(n.content() == null ? "" : n.content().strip()).append("\n\n");
        exportedNotes++;
      }
      files.put("agents/" + uniqueSlug(agent.name(), usedNames) + ".md", md.toString());
    }

    String readme = "# Jizza memory export\n\n"
        + "Exported " + now + ".\n\n"
        + "- " + exportedFacts + " facts, in `facts/`, one Markdown file per category\n"
        + "- " + subjects.size() + " people and things, in `people-and-things.md`\n"
        + "- " + exportedNotes + " agent notes, in `agents/`, one file per agent\n"
        + (withheld > 0 ? "- " + withheld + " restricted facts were left out\n" : "")
        + "\nImport this zip into Jizza from Memory → Import to bring it back.\n";

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      put(zip, "README.md", readme);
      for (var f : files.entrySet()) put(zip, f.getKey(), f.getValue());
    } catch (IOException e) {
      throw new IllegalStateException("Could not build the memory export", e);
    }
    String date = now.toString().substring(0, 10);
    return new Export("jizza-memory-" + date + ".zip", out.toByteArray());
  }

  private static void put(ZipOutputStream zip, String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry("jizza-memory/" + name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static boolean fromFile(KnowledgeEntry n) {
    return n.data() != null && "file".equals(n.data().path("source").asText(null));
  }

  private static String uniqueSlug(String name, Set<String> used) {
    String base = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
        .replaceAll("^-+|-+$", "");
    if (base.isEmpty()) base = "agent";
    String slug = base;
    for (int i = 2; !used.add(slug); i++) slug = base + "-" + i;
    return slug;
  }

  private static String oneLine(String s) {
    return s == null ? "" : s.replaceAll("\\s*\\R\\s*", " ").strip();
  }

  static String heading(MemoryCategory c) {
    return switch (c) {
      case PROFILE -> "Profile";
      case PREFERENCE -> "Preferences";
      case RELATIONSHIP -> "People and relationships";
      case FINANCE -> "Finance";
      case HEALTH -> "Health";
      case PROFESSIONAL -> "Work";
      case PROJECT -> "Projects";
      case TASK -> "Tasks";
      case GENERAL -> "Other";
      case INSTRUCTION -> "Standing instructions";
    };
  }

  // ── Import ──────────────────────────────────────────────────────────────────────────────────

  /** Pasted text from another assistant. */
  public ImportResult importText(UUID userId, String text, String provider) {
    if (text == null || text.isBlank()) throw new ValidationException("Paste what the other assistant gave you");
    if (text.length() > MAX_IMPORT_CHARS) {
      throw new ValidationException("That is too much to import at once; split it into parts under "
          + MAX_IMPORT_CHARS / 1000 + "k characters");
    }
    Importer importer = new Importer(userId, sourceRef(provider));
    importer.facts(text, null);
    return importer.result();
  }

  /** An uploaded Markdown or text file, or a zip such as a Jizza export. */
  public ImportResult importFile(UUID userId, String filename, byte[] bytes, String provider) {
    if (bytes == null || bytes.length == 0) throw new ValidationException("That file is empty");
    if (bytes.length > MAX_ZIP_BYTES) throw new ValidationException("That file is too big to import (5 MB at most)");
    String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    if (!name.endsWith(".zip")) {
      if (!(name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".txt"))) {
        throw new ValidationException("Import a .md, .txt or .zip file");
      }
      return importText(userId, new String(bytes, StandardCharsets.UTF_8), provider);
    }
    Importer importer = new Importer(userId, sourceRef(provider == null || provider.isBlank() ? filename : provider));
    readZip(bytes, (entry, text) -> {
      String path = entry.toLowerCase(Locale.ROOT);
      String file = path.substring(path.lastIndexOf('/') + 1);
      if (file.equals("readme.md")) return;
      if (path.contains("agents/")) {
        importer.notes(text);
      } else if (path.contains("people-and-things")) {
        return; // names only; the facts that mention them carry what matters
      } else {
        importer.facts(text, categoryOfFile(file));
      }
    });
    return importer.result();
  }

  private interface EntryHandler {
    void accept(String name, String text);
  }

  private static void readZip(byte[] bytes, EntryHandler handler) {
    int entries = 0;
    long total = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        if (e.isDirectory()) continue;
        String name = e.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("__macosx/") || !(lower.endsWith(".md") || lower.endsWith(".txt"))) continue;
        if (++entries > MAX_ZIP_ENTRIES) throw new ValidationException("That zip has too many files to import");
        byte[] content = readBounded(zip, MAX_IMPORT_CHARS * 4L);
        total += content.length;
        if (total > MAX_IMPORT_CHARS * 4L) throw new ValidationException("That zip holds too much to import at once");
        handler.accept(name, new String(content, StandardCharsets.UTF_8));
      }
    } catch (IOException ex) {
      throw new ValidationException("That zip could not be read");
    }
  }

  /** Reads one entry without trusting its declared size, so a zip bomb stops at the cap. */
  private static byte[] readBounded(InputStream in, long cap) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0) {
      if (out.size() + n > cap) throw new ValidationException("A file in that zip is too big to import");
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  private static String sourceRef(String provider) {
    String p = provider == null ? "" : provider.replaceAll("[^\\p{L}\\p{N} ._-]", "").strip();
    if (p.length() > 60) p = p.substring(0, 60);
    return p.isEmpty() ? "import" : "import:" + p;
  }

  static MemoryCategory categoryOfFile(String file) {
    String base = file.replaceAll("\\.(md|txt)$", "");
    for (MemoryCategory c : MemoryCategory.values()) {
      if (c.name().toLowerCase(Locale.ROOT).equals(base)) return safe(c);
    }
    return categoryOfHeading(base.replace('-', ' ').replace('_', ' '));
  }

  private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");
  private static final Pattern BOLD_HEADING = Pattern.compile("^\\*\\*([^*]{1,60})\\*\\*:?$");
  private static final Pattern BULLET = Pattern.compile("^(?:[-*+•]|\\d{1,3}[.)])\\s+(.*)$");
  /** "[2024-03-01] - fact", the shape some assistants date their memories in. */
  private static final Pattern DATED = Pattern.compile("^\\[[^\\]]{1,40}\\]\\s*[-:–]?\\s*(.*)$");
  private static final Pattern EXPORT_TAGS = Pattern.compile("\\s*_\\((?:about [^)]*|unconfirmed|waiting for review)(?:; [^)]*)?\\)_$");

  /** Maps a heading another assistant used onto a category. Never onto a standing instruction. */
  static MemoryCategory categoryOfHeading(String heading) {
    String h = heading.toLowerCase(Locale.ROOT);
    if (h.matches(".*(preferen|likes|style|tone|instruction|how i like).*")) return MemoryCategory.PREFERENCE;
    if (h.matches(".*(people|relationship|family|friend|contact).*")) return MemoryCategory.RELATIONSHIP;
    if (h.matches(".*(financ|money|budget|invest|bank).*")) return MemoryCategory.FINANCE;
    if (h.matches(".*(health|medical|fitness|diet).*")) return MemoryCategory.HEALTH;
    if (h.matches(".*(work|professional|career|job|business|employ).*")) return MemoryCategory.PROFESSIONAL;
    if (h.matches(".*(project|goal|plan).*")) return MemoryCategory.PROJECT;
    if (h.matches(".*(task|to.?do|reminder).*")) return MemoryCategory.TASK;
    if (h.matches(".*(profile|about me|identity|personal|background|who i am|bio).*")) return MemoryCategory.PROFILE;
    return MemoryCategory.GENERAL;
  }

  private static MemoryCategory safe(MemoryCategory c) {
    return c == MemoryCategory.INSTRUCTION ? MemoryCategory.PREFERENCE : c;
  }

  /** The facts in a pasted list: one per bullet or line, under the category of its heading. */
  static List<ParsedFact> parse(String text, MemoryCategory fallback) {
    List<ParsedFact> out = new ArrayList<>();
    MemoryCategory current = fallback == null ? MemoryCategory.GENERAL : fallback;
    String[] lines = text.split("\\R");
    // When the list is bulleted, the lines around it are the other assistant talking, not facts.
    boolean bulleted = java.util.Arrays.stream(lines).anyMatch(l -> BULLET.matcher(l.strip()).matches());
    for (String raw : lines) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("```") || line.matches("^[-=*_]{3,}$")) continue;
      Matcher h = HEADING.matcher(line);
      Matcher b = BOLD_HEADING.matcher(line);
      if (h.matches() || b.matches()) {
        // A Jizza export's own title line repeats its file's category; don't let it reset to Other.
        if (fallback == null) current = categoryOfHeading((h.matches() ? h : b).group(1));
        continue;
      }
      Matcher bullet = BULLET.matcher(line);
      if (bullet.matches()) line = bullet.group(1).strip();
      else if (bulleted) continue;
      Matcher dated = DATED.matcher(line);
      if (dated.matches()) line = dated.group(1).strip();
      line = EXPORT_TAGS.matcher(line).replaceFirst("").strip();
      if (line.length() < 3 || line.endsWith(":") && line.length() < 60) continue;
      if (line.length() > MAX_FACT_CHARS) line = line.substring(0, MAX_FACT_CHARS);
      out.add(new ParsedFact(current, line));
    }
    return out;
  }

  record ParsedFact(MemoryCategory category, String content) {}

  /** One import's running tally. */
  private final class Importer {
    private final UUID userId;
    private final String sourceRef;
    private final Set<String> seen = new LinkedHashSet<>();
    private final List<String> problems = new ArrayList<>();
    private int created;
    private int duplicates;
    private int pending;
    private int notes;
    private int skipped;
    private List<AgentDto> team;

    Importer(UUID userId, String sourceRef) {
      this.userId = userId;
      this.sourceRef = sourceRef;
    }

    void facts(String text, MemoryCategory category) {
      for (ParsedFact f : parse(text, category)) {
        if (!seen.add(f.category() + "|" + f.content().toLowerCase(Locale.ROOT))) continue;
        if (seen.size() > MAX_IMPORT_FACTS) {
          skipped++;
          problem("Only the first " + MAX_IMPORT_FACTS + " facts were imported");
          continue;
        }
        try {
          MemoryWrite w = store.remember(userId, new MemoryDraft(f.category(), f.content(), null, null,
              null, IMPORTED_CONFIDENCE, null, MemorySource.IMPORT, sourceRef, false, List.of(), null,
              null), Actor.user(userId));
          if (w.outcome() == MemoryWriteOutcome.DUPLICATE) duplicates++;
          else if (w.outcome() == MemoryWriteOutcome.PENDING_CONFLICT) pending++;
          else created++;
        } catch (ValidationException e) {
          skipped++;
          problem(e.getMessage());
        }
      }
    }

    /** An agent's notes from a Jizza export: back to the agent of that name, else to Jizza. */
    void notes(String text) {
      String agentName = null;
      String title = null;
      StringBuilder body = new StringBuilder();
      List<String[]> found = new ArrayList<>();
      for (String line : text.split("\\R", -1)) {
        if (agentName == null && line.startsWith("# ")) {
          agentName = line.substring(2).strip();
        } else if (line.startsWith("## ")) {
          if (title != null) found.add(new String[] {title, body.toString().strip()});
          title = line.substring(3).strip();
          body.setLength(0);
        } else if (title != null) {
          body.append(line).append('\n');
        }
      }
      if (title != null) found.add(new String[] {title, body.toString().strip()});
      if (found.isEmpty()) return;

      AgentDto agent = agentNamed(agentName);
      Set<String> existing = knowledge.list(agent.id(), EXPORT_LIMIT).stream()
          .map(n -> n.title() + "\n" + n.content()).collect(Collectors.toSet());
      for (String[] n : found) {
        String noteTitle = n[0].length() > 200 ? n[0].substring(0, 200) : n[0];
        String content = n[1].length() > MAX_NOTE_CHARS ? n[1].substring(0, MAX_NOTE_CHARS) : n[1];
        if (content.isBlank()) continue;
        if (existing.contains(noteTitle + "\n" + content)) {
          duplicates++;
          continue;
        }
        knowledge.upsert(agent.id(), KnowledgeEntry.create(userId, agent.id(), noteTitle, content,
            null, DataClassification.PII));
        notes++;
      }
    }

    private AgentDto agentNamed(String name) {
      if (team == null) team = agents.list(userId);
      Optional<AgentDto> match = name == null ? Optional.empty() : team.stream()
          .filter(a -> a.name() != null && a.name().equalsIgnoreCase(name)).findFirst();
      return match.orElseGet(() -> agents.orchestrator(userId));
    }

    private void problem(String message) {
      if (message != null && problems.size() < 5 && !problems.contains(message)) problems.add(message);
    }

    ImportResult result() {
      return new ImportResult(created, duplicates, pending, notes, skipped, List.copyOf(problems));
    }
  }
}

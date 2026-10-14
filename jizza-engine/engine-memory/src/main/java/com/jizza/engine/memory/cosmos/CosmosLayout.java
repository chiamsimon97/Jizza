package com.jizza.engine.memory.cosmos;

import com.jizza.engine.api.cosmos.BodyLink;
import com.jizza.engine.api.cosmos.BodyType;
import com.jizza.engine.api.cosmos.CelestialBody;
import com.jizza.engine.api.cosmos.CosmosAgent;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.FileEntity;
import com.jizza.engine.persistence.entity.MemorySubjectEntity;
import com.jizza.engine.persistence.entity.MemorySubjectRelationEntity;
import com.jizza.engine.persistence.support.CosmosQueries.FactRef;
import com.jizza.engine.persistence.support.CosmosQueries.NoteRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Where everything sits in one user's Cosmos. The placement rules live here and only here, so the
 * snapshot's counts, a body's page of items and a search hit's path always agree.
 *
 * <ul>
 *   <li>The sun is Jizza; each enabled specialist is a planet, ordered by key.</li>
 *   <li>A subject orbits the planet of its first domain that an agent holds, or the sun when every
 *       agent can see it (no domains) or no agent holds its domain.</li>
 *   <li>A fact orbits the first subject it is about; with no subject, the planet of its first
 *       domain; with neither, the sun. It is tethered to every other subject it is about and to
 *       every other planet that can see it.</li>
 *   <li>A note orbits the file it was read from, or its agent's Notes.</li>
 * </ul>
 */
final class CosmosLayout {

  final AgentEntity orchestrator;
  final List<AgentEntity> specialists;
  final String sunId;
  final Map<UUID, AgentEntity> agentsById = new LinkedHashMap<>();
  final Map<String, AgentEntity> agentByDomain = new LinkedHashMap<>();
  final Map<String, CelestialBody> bodies = new LinkedHashMap<>();
  final List<BodyLink> links = new ArrayList<>();

  CosmosLayout(AgentEntity orchestrator, List<AgentEntity> specialists) {
    this.orchestrator = orchestrator;
    this.specialists = specialists.stream()
        .sorted(java.util.Comparator.comparing(AgentEntity::getKey)).toList();
    this.sunId = BodyType.SUN.name() + ":" + orchestrator.getId();
    agentsById.put(orchestrator.getId(), orchestrator);
    for (AgentEntity a : this.specialists) {
      agentsById.put(a.getId(), a);
      String domain = domainOf(a);
      if (domain != null) agentByDomain.putIfAbsent(domain, a);
    }

    put(new CelestialBody(sunId, BodyType.SUN, null, orchestrator.getName(), orchestrator.getKey(),
        0, 0, 0, null, Map.of("agentId", orchestrator.getId().toString())));
    for (AgentEntity a : this.specialists) {
      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put("agentId", a.getId().toString());
      if (domainOf(a) != null) meta.put("domain", domainOf(a));
      put(new CelestialBody(agentBodyId(a), BodyType.AGENT, sunId, a.getName(), a.getKey(), 1, 0, 0,
          null, meta));
    }
  }

  // ── bodies ──────────────────────────────────────────────────────────────────────────────────

  CosmosAgent cosmosAgent(AgentEntity a) {
    return new CosmosAgent(a.getId(), a.getKey(), a.getName(), domainOf(a), a.isOrchestrator(),
        agentBodyId(a));
  }

  List<AgentEntity> allAgents() {
    List<AgentEntity> all = new ArrayList<>();
    all.add(orchestrator);
    all.addAll(specialists);
    return all;
  }

  String agentBodyId(AgentEntity a) {
    return a.isOrchestrator() ? sunId : BodyType.AGENT.name() + ":" + a.getId();
  }

  void addSubjects(List<MemorySubjectEntity> subjects, List<MemorySubjectRelationEntity> relations) {
    for (MemorySubjectEntity s : subjects) {
      String parent = homeForDomains(List.of(s.getDomains()));
      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put("subjectId", s.getId().toString());
      meta.put("kind", s.getKind().name().toLowerCase(Locale.ROOT));
      if (s.getRelation() != null) meta.put("relation", s.getRelation());
      if (s.getAliases() != null && s.getAliases().length > 0) meta.put("aliases", List.of(s.getAliases()));
      if (s.getDomains() != null && s.getDomains().length > 0) meta.put("domains", List.of(s.getDomains()));
      if (s.getConnectionId() != null) meta.put("connectionId", s.getConnectionId().toString());
      put(new CelestialBody(subjectBodyId(s.getId()), BodyType.SUBJECT, parent, s.getName(),
          keyOf(parent), tierUnder(parent), 0, 0, s.getUpdatedAt(), meta));
    }
    for (MemorySubjectRelationEntity r : relations) {
      String from = subjectBodyId(r.getFromSubjectId());
      String to = subjectBodyId(r.getToSubjectId());
      if (bodies.containsKey(from) && bodies.containsKey(to)) links.add(new BodyLink(from, to, r.getRelation()));
    }
  }

  void addNotesAndFiles(List<NoteRef> notes, List<FileEntity> files) {
    Set<UUID> withNotes = new LinkedHashSet<>();
    for (NoteRef n : notes) if (n.fileId() == null) withNotes.add(n.agentId());
    for (AgentEntity a : allAgents()) {
      if (!withNotes.contains(a.getId())) continue;
      String parent = agentBodyId(a);
      put(new CelestialBody(notesBodyId(a.getId()), BodyType.NOTES, parent, "Notes", a.getKey(),
          tierUnder(parent), 0, 0, null, Map.of("agentId", a.getId().toString())));
    }
    for (FileEntity f : files) {
      AgentEntity owner = f.getAgentId() == null ? null : agentsById.get(f.getAgentId());
      if (owner == null) continue;
      String parent = agentBodyId(owner);
      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put("fileId", f.getId().toString());
      meta.put("agentId", owner.getId().toString());
      if (f.getContentType() != null) meta.put("contentType", f.getContentType());
      put(new CelestialBody(fileBodyId(f.getId()), BodyType.FILE, parent,
          f.getFilename() == null ? "File" : f.getFilename(), owner.getKey(), tierUnder(parent), 0, 0,
          f.getCreatedAt(), meta));
    }
  }

  /**
   * Adds bodies from a provider, placing each under the body it names. A body whose parent is not
   * (yet) known goes under the agent it was contributed for.
   */
  void addProvided(AgentEntity agent, Collection<CelestialBody> provided) {
    Map<String, CelestialBody> pending = new LinkedHashMap<>();
    for (CelestialBody b : provided) pending.put(b.bodyId(), b);
    boolean progress = true;
    while (!pending.isEmpty() && progress) {
      progress = false;
      for (var it = pending.values().iterator(); it.hasNext(); ) {
        CelestialBody b = it.next();
        if (b.parentBodyId() != null && bodies.containsKey(b.parentBodyId())) {
          put(b.under(b.parentBodyId(), tierUnder(b.parentBodyId())));
          it.remove();
          progress = true;
        }
      }
    }
    String fallback = agentBodyId(agent);
    for (CelestialBody b : pending.values()) put(b.under(fallback, tierUnder(fallback)));
  }

  /** A subject with a Connector is linked to that connector's moon under every agent that has it. */
  void linkConnectors() {
    Map<String, List<String>> connectorBodies = new LinkedHashMap<>();
    for (CelestialBody b : bodies.values()) {
      if (b.type() == BodyType.CONNECTOR && b.meta() != null && b.meta().get("connectionId") != null) {
        connectorBodies.computeIfAbsent(b.meta().get("connectionId").toString(), k -> new ArrayList<>())
            .add(b.bodyId());
      }
    }
    for (CelestialBody b : List.copyOf(bodies.values())) {
      if (b.type() != BodyType.SUBJECT || b.meta() == null || b.meta().get("connectionId") == null) continue;
      for (String target : connectorBodies.getOrDefault(b.meta().get("connectionId").toString(), List.of())) {
        links.add(new BodyLink(b.bodyId(), target, "connector"));
      }
    }
  }

  // ── placement ───────────────────────────────────────────────────────────────────────────────

  String homeOf(FactRef f) {
    for (UUID s : f.subjectIds()) {
      if (bodies.containsKey(subjectBodyId(s))) return subjectBodyId(s);
    }
    return homeForDomains(f.domains());
  }

  String homeOf(NoteRef n) {
    if (n.fileId() != null && bodies.containsKey(fileBodyId(n.fileId()))) return fileBodyId(n.fileId());
    String notes = notesBodyId(n.agentId());
    if (bodies.containsKey(notes)) return notes;
    AgentEntity owner = agentsById.get(n.agentId());
    return owner == null ? sunId : agentBodyId(owner);
  }

  /** The other subjects a fact is about, and the other planets that can see it. */
  List<String> tethersOf(FactRef f, String home) {
    Set<String> out = new LinkedHashSet<>();
    for (UUID s : f.subjectIds()) {
      String id = subjectBodyId(s);
      if (!id.equals(home) && bodies.containsKey(id)) out.add(id);
    }
    String homePlanet = planetOf(home);
    for (String d : f.domains()) {
      AgentEntity a = agentByDomain.get(d);
      if (a == null) continue;
      String planet = agentBodyId(a);
      if (!planet.equals(homePlanet)) out.add(planet);
    }
    return List.copyOf(out);
  }

  /** Whose colour a fact takes: the first agent that holds one of its domains, else its home's. */
  String agentKeyOf(FactRef f, String home) {
    for (String d : f.domains()) {
      AgentEntity a = agentByDomain.get(d);
      if (a != null) return a.getKey();
    }
    return keyOf(home);
  }

  /** Sun first, then each body down to {@code bodyId}. */
  List<String> pathTo(String bodyId) {
    List<String> path = new ArrayList<>();
    String at = bodyId;
    int guard = 0;
    while (at != null && guard++ < 16) {
      path.addFirst(at);
      CelestialBody b = bodies.get(at);
      at = b == null ? null : b.parentBodyId();
    }
    return path;
  }

  /** The planet (or sun) a body sits under. */
  String planetOf(String bodyId) {
    for (String id : pathTo(bodyId)) {
      CelestialBody b = bodies.get(id);
      if (b != null && b.type() == BodyType.AGENT) return id;
    }
    return sunId;
  }

  private String homeForDomains(List<String> domains) {
    for (String d : domains) {
      AgentEntity a = agentByDomain.get(d);
      if (a != null) return agentBodyId(a);
    }
    return sunId;
  }

  private String keyOf(String bodyId) {
    CelestialBody b = bodies.get(bodyId);
    return b == null ? orchestrator.getKey() : b.agentKey();
  }

  private int tierUnder(String parent) {
    CelestialBody p = bodies.get(parent);
    return p == null ? 1 : p.tier() + 1;
  }

  private void put(CelestialBody b) {
    bodies.put(b.bodyId(), b);
  }

  static java.time.Instant later(java.time.Instant a, java.time.Instant b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.isAfter(b) ? a : b;
  }

  static String domainOf(AgentEntity a) {
    return a.getDomain() == null || a.getDomain().isBlank() ? null : a.getDomain().strip().toLowerCase(Locale.ROOT);
  }

  static String subjectBodyId(UUID id) { return BodyType.SUBJECT.name() + ":" + id; }

  static String notesBodyId(UUID agentId) { return BodyType.NOTES.name() + ":" + agentId; }

  static String fileBodyId(UUID fileId) { return BodyType.FILE.name() + ":" + fileId; }
}

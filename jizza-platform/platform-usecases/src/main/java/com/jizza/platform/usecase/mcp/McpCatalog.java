package com.jizza.platform.usecase.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jizza.engine.api.mcp.McpAuthType;
import com.jizza.engine.api.mcp.McpServers;
import com.jizza.engine.api.util.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Ready-made MCP servers anyone can install into the account they are in with one click, kept in
 * {@code mcp/catalog.json}: public remote servers from the services themselves, grouped as
 * personal, professional, finance and health. Installing one is an ordinary
 * {@link McpServerUseCase#create}, so the same validation, guardrails and audit apply.
 *
 * <p>Only streamable HTTP servers belong here, since that is the transport the app speaks.
 */
@Service
public class McpCatalog {

  static final String RESOURCE = "mcp/catalog.json";
  static final List<String> CATEGORIES = List.of("personal", "professional", "finance", "health");

  /**
   * One server in the catalog.
   *
   * @param ownClient   the service hands out no OAuth client, so the person brings their own
   * @param secretLabel what to call the token or key a BEARER or HEADER server asks for
   * @param agentKeys   the agents that get it by default; ones the person doesn't have are skipped
   * @param note        what to do before or after installing, shown in the install form
   */
  public record Entry(String id, String name, String publisher, String category,
                      String description, String url, McpAuthType authType, String headerName,
                      String secretLabel, boolean ownClient, String oauthScopes,
                      List<String> agentKeys, String note, String docsUrl) {}

  /** The catalog, and the address an OAuth client of the person's own must allow as redirect. */
  public record Catalog(List<Entry> entries, String redirectUri) {}

  private static final List<Entry> ENTRIES = load();

  private final McpServers servers;

  public McpCatalog(McpServers servers) {
    this.servers = servers;
  }

  public Catalog catalog() {
    return new Catalog(ENTRIES, servers.oauthRedirectUri());
  }

  static List<Entry> entries() {
    return ENTRIES;
  }

  private static List<Entry> load() {
    try (InputStream in = McpCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) return List.of();
      return List.copyOf(Json.mapper().readValue(in, new TypeReference<List<Entry>>() {}));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

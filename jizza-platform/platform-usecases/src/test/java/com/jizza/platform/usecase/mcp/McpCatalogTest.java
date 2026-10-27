package com.jizza.platform.usecase.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.mcp.McpAuthType;
import com.jizza.platform.usecase.agent.AgentTeamUseCase;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpCatalogTest {

  private final List<McpCatalog.Entry> entries = McpCatalog.entries();

  @Test
  void everyCategoryHasServers() {
    assertThat(entries).hasSizeGreaterThanOrEqualTo(20);
    for (String category : McpCatalog.CATEGORIES) {
      assertThat(entries).filteredOn(e -> category.equals(e.category())).hasSizeGreaterThanOrEqualTo(3);
    }
    assertThat(entries).allSatisfy(e -> assertThat(McpCatalog.CATEGORIES).contains(e.category()));
  }

  @Test
  void entriesAreCompleteAndUnique() {
    Set<String> ids = new HashSet<>();
    Set<String> urls = new HashSet<>();
    Set<String> agents = Set.of(AgentTeamUseCase.JIZZA, AgentTeamUseCase.HEALTH,
        AgentTeamUseCase.FINANCE, AgentTeamUseCase.PROFESSIONAL);
    for (McpCatalog.Entry e : entries) {
      assertThat(ids.add(e.id())).as("duplicate id %s", e.id()).isTrue();
      assertThat(urls.add(e.url())).as("duplicate url %s", e.url()).isTrue();
      assertThat(e.id()).matches("[a-z0-9-]+");
      assertThat(e.name()).isNotBlank();
      assertThat(e.publisher()).isNotBlank();
      assertThat(e.description()).isNotBlank();
      assertThat(e.authType()).isNotNull();
      assertThat(URI.create(e.url()).getScheme()).isEqualTo("https");
      assertThat(e.agentKeys()).contains(AgentTeamUseCase.JIZZA);
      assertThat(agents).containsAll(e.agentKeys());
      if (e.docsUrl() != null) assertThat(URI.create(e.docsUrl()).getScheme()).isEqualTo("https");
      if (e.authType() == McpAuthType.HEADER) assertThat(e.headerName()).isNotBlank();
      if (e.ownClient()) assertThat(e.authType()).isEqualTo(McpAuthType.OAUTH);
    }
  }

  @Test
  void kiteIsInTheCatalog() {
    assertThat(entries).anySatisfy(e -> {
      assertThat(e.id()).isEqualTo("kite");
      assertThat(e.url()).isEqualTo("https://mcp.kite.trade/mcp");
      assertThat(e.agentKeys()).contains(AgentTeamUseCase.FINANCE);
    });
  }
}

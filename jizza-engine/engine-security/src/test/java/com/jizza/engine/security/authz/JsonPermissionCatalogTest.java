package com.jizza.engine.security.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.enums.WorkspaceType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The permissions file is read once at startup; a broken file must stop the server, loudly. */
class JsonPermissionCatalogTest {

  private static String shipped() throws IOException {
    try (InputStream in = JsonPermissionCatalogTest.class.getClassLoader()
        .getResourceAsStream(JsonPermissionCatalog.LOCATION)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static JsonPermissionCatalog load(String json) {
    return JsonPermissionCatalog.fromBytes(json.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void theShippedFileDefinesEveryPermissionTheCodeUses() {
    var catalog = new JsonPermissionCatalog();
    for (String name : Permissions.ALL) assertThat(catalog.find(name)).as(name).isPresent();
    assertThat(catalog.version()).hasSize(64);
  }

  @Test
  void grantsFollowRoleAndAccountType() {
    var catalog = new JsonPermissionCatalog();
    assertThat(catalog.granted(WorkspaceRole.OWNER, WorkspaceType.PERSONAL))
        .contains(Permissions.CONNECTOR_VIEW, Permissions.CONNECTOR_DELETE)
        .doesNotContain(Permissions.MEMBERS_VIEW);
    assertThat(catalog.granted(WorkspaceRole.OWNER, WorkspaceType.ORGANIZATION))
        .contains(Permissions.MEMBERS_INVITE, Permissions.MEMBERS_REMOVE);
    assertThat(catalog.granted(WorkspaceRole.DEVELOPER, WorkspaceType.ORGANIZATION))
        .contains(Permissions.CONNECTOR_CREATE)
        .doesNotContain(Permissions.MEMBERS_INVITE);
    assertThat(catalog.granted(WorkspaceRole.USER, WorkspaceType.ORGANIZATION))
        .doesNotContain(Permissions.CONNECTOR_VIEW, Permissions.MEMBERS_INVITE);
  }

  @Test
  void anUnknownDependencyIsRefused() throws IOException {
    String broken = shipped().replaceFirst("\"dependencies\": \\[\"connector_view\"]",
        "\"dependencies\": [\"connector_nothing\"]");
    assertThatThrownBy(() -> load(broken)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connector_nothing");
  }

  @Test
  void anUnknownFieldIsRefused() throws IOException {
    String broken = shipped().replaceFirst("\"module\": \"connector\"",
        "\"module\": \"connector\", \"modul\": \"typo\"");
    assertThatThrownBy(() -> load(broken)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void anUnknownRoleIsRefused() throws IOException {
    String broken = shipped().replaceFirst("\"owner\", \"admin\", \"developer\"",
        "\"owner\", \"admin\", \"intern\"");
    assertThatThrownBy(() -> load(broken)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("intern");
  }

  @Test
  void aPermissionTheCodeUsesCannotBeRemoved() throws IOException {
    String broken = shipped().replace("\"name\": \"members_remove\"", "\"name\": \"members_gone\"");
    assertThatThrownBy(() -> load(broken)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("members_remove");
  }

  @Test
  void aDependencyGrantedToFewerRolesIsRefused() throws IOException {
    // connector_view narrowed to the owner while connector_create still reaches admins.
    String broken = shipped().replaceFirst(
        "\"name\": \"connector_view\"([^}]*)\"permittedRoles\": \\[\"owner\", \"admin\", \"developer\"]",
        "\"name\": \"connector_view\"$1\"permittedRoles\": [\"owner\"]");
    assertThat(broken).isNotEqualTo(shipped());
    assertThatThrownBy(() -> load(broken)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connector_view");
  }

  @Test
  void commentsAreAllowedButNotGarbage() {
    assertThatThrownBy(() -> load("not json")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> load("[]")).isInstanceOf(IllegalStateException.class);
  }
}

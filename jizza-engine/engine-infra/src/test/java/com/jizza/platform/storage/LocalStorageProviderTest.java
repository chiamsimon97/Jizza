package com.jizza.platform.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.common.error.NotFoundException;
import com.jizza.common.error.ValidationException;
import com.jizza.platform.config.PlatformProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalStorageProviderTest {

  private LocalStorageProvider provider(Path root) {
    PlatformProperties props = new PlatformProperties();
    props.getStorage().setRoot(root.toString());
    return new LocalStorageProvider(props);
  }

  @Test
  void roundTripsAFile(@TempDir Path root) {
    var storage = provider(root);
    String ref = storage.put("openapi contents".getBytes(StandardCharsets.UTF_8), "petstore.json");

    assertThat(new String(storage.get(ref), StandardCharsets.UTF_8)).isEqualTo("openapi contents");
    assertThat(ref).endsWith(".json");
  }

  @Test
  void neverUsesTheCallerSuppliedNameAsThePath(@TempDir Path root) {
    var storage = provider(root);
    String ref = storage.put("x".getBytes(StandardCharsets.UTF_8), "../../etc/passwd");

    assertThat(ref).doesNotContain("..").doesNotContain("passwd");
    assertThat(root.resolve(ref).normalize()).startsWith(root);
  }

  @Test
  void refusesAReferenceThatTriesToEscapeTheRoot(@TempDir Path root) {
    var storage = provider(root);
    assertThatThrownBy(() -> storage.get("../../../etc/passwd"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void reportsAMissingFileAsNotFound(@TempDir Path root) {
    assertThatThrownBy(() -> provider(root).get("nothing-here"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void deleteIsIdempotent(@TempDir Path root) {
    var storage = provider(root);
    String ref = storage.put("x".getBytes(StandardCharsets.UTF_8), "a.txt");
    storage.delete(ref);
    storage.delete(ref);
    assertThatThrownBy(() -> storage.get(ref)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void createsTheRootIfItIsNotThere(@TempDir Path root) throws Exception {
    Path nested = root.resolve("deep/storage");
    provider(nested);
    assertThat(Files.isDirectory(nested)).isTrue();
  }
}

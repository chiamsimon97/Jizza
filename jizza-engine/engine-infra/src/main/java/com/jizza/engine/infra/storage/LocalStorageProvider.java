package com.jizza.engine.infra.storage;

import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.storage.StorageProvider;
import com.jizza.engine.infra.config.PlatformProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Local-filesystem object storage (R47).
 *
 * <p>Stored names are generated, never taken from the upload: a caller-supplied filename is the
 * classic path-traversal vector, and the original name belongs in the {@code files} row rather
 * than on disk.
 */
@Component
public class LocalStorageProvider implements StorageProvider {

  private final Path root;

  public LocalStorageProvider(PlatformProperties props) {
    this.root = Path.of(props.getStorage().getRoot()).toAbsolutePath().normalize();
    try {
      Files.createDirectories(root);
    } catch (IOException e) {
      throw new IllegalStateException("Could not create the storage root at " + root, e);
    }
  }

  @Override
  public String put(byte[] data, String name) {
    String ref = UUID.randomUUID() + extensionOf(name);
    try {
      Files.write(resolve(ref), data);
    } catch (IOException e) {
      throw new IllegalStateException("Could not store " + ref, e);
    }
    return ref;
  }

  @Override
  public byte[] get(String ref) {
    try {
      return Files.readAllBytes(resolve(ref));
    } catch (java.nio.file.NoSuchFileException e) {
      throw new NotFoundException("Stored file", ref);
    } catch (IOException e) {
      throw new IllegalStateException("Could not read " + ref, e);
    }
  }

  @Override
  public void delete(String ref) {
    try {
      Files.deleteIfExists(resolve(ref));
    } catch (IOException e) {
      throw new IllegalStateException("Could not delete " + ref, e);
    }
  }

  /** Resolves inside the root and refuses anything that escapes it. */
  private Path resolve(String ref) {
    Path path = root.resolve(ref).normalize();
    if (!path.startsWith(root)) {
      throw new com.jizza.engine.api.error.ValidationException("Invalid storage reference");
    }
    return path;
  }

  private String extensionOf(String name) {
    if (name == null) return "";
    int dot = name.lastIndexOf('.');
    if (dot < 0 || dot == name.length() - 1) return "";
    String extension = name.substring(dot + 1);
    return extension.matches("[A-Za-z0-9]{1,8}") ? "." + extension.toLowerCase(java.util.Locale.ROOT) : "";
  }
}

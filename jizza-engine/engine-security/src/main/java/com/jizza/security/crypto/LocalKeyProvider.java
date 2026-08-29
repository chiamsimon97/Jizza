package com.jizza.security.crypto;

import com.jizza.common.crypto.KeyProvider;
import com.jizza.security.config.SecurityProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves the master key for local-first running, in order of preference: a PKCS12 keystore, a
 * key file outside the repository, then a dev-only environment variable. Nothing here ever reads a
 * key from the source tree.
 *
 * <p>The key file supports rotation: it holds {@code <keyId>=<base64>} lines plus {@code active=},
 * so ciphertext written under a retired key still decrypts.
 */
@Component
public class LocalKeyProvider implements KeyProvider {

  private static final Logger log = LoggerFactory.getLogger(LocalKeyProvider.class);
  private static final String ACTIVE = "active";

  private final Map<String, SecretKey> keys = new HashMap<>();
  private final String activeKeyId;

  public LocalKeyProvider(SecurityProperties props) {
    if (props.getKeystorePath() != null && !props.getKeystorePath().isBlank()) {
      activeKeyId = loadFromKeystore(props);
    } else if (props.getMasterKeyFile() != null && !props.getMasterKeyFile().isBlank()) {
      activeKeyId = loadFromFile(Path.of(props.getMasterKeyFile()));
    } else if (props.getMasterKey() != null && !props.getMasterKey().isBlank()) {
      if (!props.isAllowEnvMasterKey()) {
        throw new IllegalStateException(
            "An environment-supplied master key is dev-only; configure jizza.security.keystore-path "
                + "or master-key-file for this profile");
      }
      log.warn("Using the environment-supplied master key. This is intended for development only; "
          + "configure a keystore or key file before handling real credentials.");
      keys.put("env", new SecretKeySpec(Base64.getDecoder().decode(props.getMasterKey()), "AES"));
      activeKeyId = "env";
    } else {
      throw new IllegalStateException(
          "No master key configured. Set jizza.security.keystore-path, master-key-file, or "
              + "(development only) JIZZA_MASTER_KEY. See README.md.");
    }
  }

  @Override
  public SecretKey masterKey() { return keyById(activeKeyId); }

  @Override
  public String activeKeyId() { return activeKeyId; }

  @Override
  public SecretKey keyById(String keyId) {
    SecretKey key = keys.get(keyId);
    if (key == null) {
      throw new IllegalStateException("Master key '" + keyId + "' is not available; ciphertext "
          + "written under it cannot be read until the key is restored");
    }
    return key;
  }

  private String loadFromKeystore(SecurityProperties props) {
    try {
      KeyStore ks = KeyStore.getInstance("PKCS12");
      char[] password = props.getKeystorePassword() == null
          ? new char[0] : props.getKeystorePassword().toCharArray();
      Path path = Path.of(props.getKeystorePath());
      if (!Files.exists(path)) {
        throw new IllegalStateException("Keystore " + path + " does not exist. Create it with "
            + "`keytool -genseckey` or point jizza.security.keystore-path elsewhere.");
      }
      try (InputStream in = Files.newInputStream(path)) {
        ks.load(in, password);
      }
      String active = null;
      var aliases = ks.aliases();
      while (aliases.hasMoreElements()) {
        String alias = aliases.nextElement();
        if (!alias.startsWith(props.getKeystoreAlias())) continue;
        var entry = ks.getKey(alias, password);
        if (entry instanceof SecretKey sk) {
          keys.put(alias, sk);
          if (active == null || alias.compareTo(active) > 0) active = alias;
        }
      }
      if (active == null) {
        throw new IllegalStateException("Keystore " + path + " has no secret key with alias prefix "
            + props.getKeystoreAlias());
      }
      return active;
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Unable to read the master key from the keystore", e);
    }
  }

  private String loadFromFile(Path path) {
    try {
      if (!Files.exists(path)) generate(path);
      Properties p = new Properties();
      try (InputStream in = Files.newInputStream(path)) {
        p.load(in);
      }
      String active = p.getProperty(ACTIVE);
      if (active == null) throw new IllegalStateException("Key file " + path + " has no 'active' entry");
      for (String name : p.stringPropertyNames()) {
        if (ACTIVE.equals(name)) continue;
        keys.put(name, new SecretKeySpec(Base64.getDecoder().decode(p.getProperty(name)), "AES"));
      }
      if (!keys.containsKey(active)) {
        throw new IllegalStateException("Key file " + path + " names active key '" + active
            + "' but does not contain it");
      }
      return active;
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Unable to read the master key file " + path, e);
    }
  }

  /** First run on a fresh machine: mint a key at 0600 rather than making the operator do it. */
  private void generate(Path path) throws Exception {
    Files.createDirectories(path.toAbsolutePath().getParent());
    KeyGenerator gen = KeyGenerator.getInstance("AES");
    gen.init(256, SecureRandom.getInstanceStrong());
    Properties p = new Properties();
    p.setProperty(ACTIVE, "k1");
    p.setProperty("k1", Base64.getEncoder().encodeToString(gen.generateKey().getEncoded()));
    try (OutputStream out = Files.newOutputStream(path)) {
      p.store(out, "Jizza master keys — keep this file out of the repository and off backups you "
          + "would not trust with every stored credential.");
    }
    restrictPermissions(path);
    log.warn("Generated a new master key at {}. Back it up: without it every stored credential "
        + "becomes unreadable.", path);
  }

  private void restrictPermissions(Path path) throws IOException {
    try {
      Files.setPosixFilePermissions(path,
          Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX filesystem; the operator owns file permissions there.
    }
  }
}

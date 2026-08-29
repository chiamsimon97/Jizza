package com.jizza.security.password;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Rejects passwords known to be compromised (R6). Local-first, so the check runs against a bundled
 * list rather than calling out to a breach API on every registration; the list is the seam where a
 * k-anonymity HIBP lookup can be added when the deployment is allowed to make that call.
 */
@Component
public class BreachedPasswordChecker {

  private final Set<String> breached = new HashSet<>();

  public BreachedPasswordChecker() {
    try (InputStream in = new ClassPathResource("security/breached-passwords.txt").getInputStream();
         BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
          breached.add(trimmed.toLowerCase(Locale.ROOT));
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("Unable to load the breached-password list", e);
    }
  }

  public boolean isBreached(String password) {
    return password != null && breached.contains(password.toLowerCase(Locale.ROOT));
  }

  public int size() { return breached.size(); }
}

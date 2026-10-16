package com.jizza.engine.security.mfa;

import com.jizza.engine.api.util.Hashing;
import com.jizza.engine.persistence.entity.MfaRecoveryCodeEntity;
import com.jizza.engine.persistence.repository.MfaRecoveryCodeRepository;
import com.jizza.engine.security.config.SecurityProperties;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time recovery codes for when the authenticator is lost.
 *
 * <p>Each code is 60 random bits shown as {@code xxxx-xxxx-xxxx}, stored only as its own SHA-256
 * and spent the moment it is used. The entropy is what makes a plain hash enough here: there is no
 * dictionary to run against it.
 */
@Component
public class RecoveryCodes {

  /** Base32 without the look-alikes 0/1/8/9, so a code read off paper is typed correctly. */
  private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
  private static final int GROUPS = 3;
  private static final int GROUP_LENGTH = 4;

  private final MfaRecoveryCodeRepository codes;
  private final SecurityProperties props;
  private final SecureRandom random = new SecureRandom();

  public RecoveryCodes(MfaRecoveryCodeRepository codes, SecurityProperties props) {
    this.codes = codes;
    this.props = props;
  }

  /** Replaces any existing set. The returned plaintext is never stored. */
  @Transactional
  public List<String> issue(UUID userId) {
    codes.deleteAllForUser(userId);
    List<String> plain = new ArrayList<>();
    for (int i = 0; i < props.getMfa().getRecoveryCodes(); i++) {
      String code = newCode();
      MfaRecoveryCodeEntity entity = new MfaRecoveryCodeEntity();
      entity.setUserId(userId);
      entity.setCodeHash(Hashing.sha256Hex(normalize(code)));
      codes.save(entity);
      plain.add(code);
    }
    return plain;
  }

  /** Spends a code if it is one of the user's unused ones. */
  @Transactional
  public boolean consume(UUID userId, String submitted) {
    if (!looksLikeRecoveryCode(submitted)) return false;
    return codes.findByUserIdAndCodeHashAndUsedAtIsNull(userId,
            Hashing.sha256Hex(normalize(submitted)))
        .map(entity -> {
          entity.setUsedAt(Instant.now());
          codes.save(entity);
          return true;
        })
        .orElse(false);
  }

  @Transactional(readOnly = true)
  public long remaining(UUID userId) {
    return codes.countByUserIdAndUsedAtIsNull(userId);
  }

  @Transactional
  public void revokeAll(UUID userId) {
    codes.deleteAllForUser(userId);
  }

  /** A TOTP code is all digits; anything with letters is treated as a recovery code. */
  public static boolean looksLikeRecoveryCode(String submitted) {
    if (submitted == null) return false;
    String n = normalize(submitted);
    return n.length() == GROUPS * GROUP_LENGTH && n.chars().anyMatch(Character::isLetter);
  }

  static String normalize(String code) {
    return code.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
  }

  private String newCode() {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g < GROUPS; g++) {
      if (g > 0) sb.append('-');
      for (int i = 0; i < GROUP_LENGTH; i++) sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
    }
    String code = sb.toString();
    // An all-digit draw would read as a TOTP code; draw again (probability ~1e-9).
    return looksLikeRecoveryCode(code) ? code : newCode();
  }
}

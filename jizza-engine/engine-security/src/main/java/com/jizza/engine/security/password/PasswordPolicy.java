package com.jizza.engine.security.password;

import com.jizza.engine.api.error.ValidationException;
import org.springframework.stereotype.Component;

/**
 * Minimum bar for a password guarding health, financial and professional data plus live third-party
 * tokens: length first, then variety, then a breach check.
 */
@Component
public class PasswordPolicy {

  private static final int MIN_LENGTH = 12;
  private static final int MAX_LENGTH = 256;

  private final BreachedPasswordChecker breached;

  public PasswordPolicy(BreachedPasswordChecker breached) {
    this.breached = breached;
  }

  public void validate(String password) {
    if (password == null || password.length() < MIN_LENGTH) {
      throw new ValidationException("Password must be at least " + MIN_LENGTH + " characters");
    }
    if (password.length() > MAX_LENGTH) {
      throw new ValidationException("Password must be at most " + MAX_LENGTH + " characters");
    }
    int classes = 0;
    if (password.chars().anyMatch(Character::isUpperCase)) classes++;
    if (password.chars().anyMatch(Character::isLowerCase)) classes++;
    if (password.chars().anyMatch(Character::isDigit)) classes++;
    if (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) classes++;
    if (classes < 3) {
      throw new ValidationException(
          "Password must combine at least three of: upper case, lower case, digits, symbols");
    }
    if (breached.isBreached(password)) {
      throw new ValidationException("That password appears in known breach lists; choose another");
    }
  }
}

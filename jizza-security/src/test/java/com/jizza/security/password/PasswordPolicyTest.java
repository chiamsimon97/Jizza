package com.jizza.security.password;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.common.error.ValidationException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

  private final PasswordPolicy policy = new PasswordPolicy(new BreachedPasswordChecker());

  @Test
  void acceptsAStrongPassword() {
    assertThatCode(() -> policy.validate("Correct-Horse-92")).doesNotThrowAnyException();
  }

  @Test
  void rejectsShortPasswords() {
    assertThatThrownBy(() -> policy.validate("Ab3!x"))
        .isInstanceOf(ValidationException.class).hasMessageContaining("at least 12");
  }

  @Test
  void rejectsPasswordsWithTooLittleVariety() {
    assertThatThrownBy(() -> policy.validate("alllowercaseletters"))
        .isInstanceOf(ValidationException.class).hasMessageContaining("three of");
  }

  @Test
  void rejectsBreachedPasswords() {
    BreachedPasswordChecker checker = new BreachedPasswordChecker();
    // Long enough and varied enough to clear the earlier rules, so the breach list is what stops it.
    org.assertj.core.api.Assertions.assertThat(checker.isBreached("Password1234")).isTrue();
    org.assertj.core.api.Assertions.assertThat(checker.isBreached("Correct-Horse-92")).isFalse();
    assertThatThrownBy(() -> policy.validate("Password1234"))
        .isInstanceOf(ValidationException.class).hasMessageContaining("breach");
  }

  @Test
  void rejectsNull() {
    assertThatThrownBy(() -> policy.validate(null)).isInstanceOf(ValidationException.class);
  }
}

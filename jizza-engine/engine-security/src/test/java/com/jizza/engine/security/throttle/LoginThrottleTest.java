package com.jizza.engine.security.throttle;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.RateLimitedException;
import com.jizza.engine.security.config.SecurityProperties;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

  private LoginThrottle throttle(int perMinute) {
    SecurityProperties props = new SecurityProperties();
    props.getLockout().setAuthRequestsPerMinute(perMinute);
    return new LoginThrottle(props);
  }

  @Test
  void allowsUpToTheConfiguredRateThenRefuses() {
    LoginThrottle throttle = throttle(3);
    for (int i = 0; i < 3; i++) {
      assertThatCode(() -> throttle.check("ip:1.2.3.4")).doesNotThrowAnyException();
    }
    assertThatThrownBy(() -> throttle.check("ip:1.2.3.4"))
        .isInstanceOf(RateLimitedException.class);
  }

  @Test
  void countsEachKeySeparately() {
    LoginThrottle throttle = throttle(1);
    throttle.check("ip:1.1.1.1");
    assertThatCode(() -> throttle.check("ip:2.2.2.2")).doesNotThrowAnyException();
  }

  @Test
  void resetClearsAKey() {
    LoginThrottle throttle = throttle(1);
    throttle.check("account:a@b.c");
    throttle.reset("account:a@b.c");
    assertThatCode(() -> throttle.check("account:a@b.c")).doesNotThrowAnyException();
  }
}

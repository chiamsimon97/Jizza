package com.jizza.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class McpErrorsTest {

  /** Stands in for the SDK's McpHttpClientTransportAuthorizationException. */
  static final class McpHttpClientTransportAuthorizationException extends RuntimeException {
    McpHttpClientTransportAuthorizationException() { super("Authorization error when sending message"); }
  }

  @Test
  void aRefusedSignInIsFoundUnderTheSdksInitializeError() {
    var wrapped = new RuntimeException("Client failed to initialize by explicit API call",
        new McpHttpClientTransportAuthorizationException());
    assertThat(McpErrors.needsSignIn(wrapped)).isTrue();
    assertThat(McpErrors.needsSignIn(new RuntimeException("HTTP 401 no bearer token"))).isTrue();
  }

  @Test
  void otherFailuresAreNotSignIns() {
    assertThat(McpErrors.needsSignIn(new RuntimeException("Client failed to initialize by explicit API call",
        new java.net.ConnectException("Connection refused")))).isFalse();
    assertThat(McpErrors.needsSignIn(new RuntimeException("Timeout after 4013 ms"))).isFalse();
  }
}

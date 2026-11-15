package com.jizza.platform.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequireHttpsFilterTest {

  private final RequireHttpsFilter filter = new RequireHttpsFilter();

  private MockHttpServletResponse run(MockHttpServletRequest request)
      throws ServletException, IOException {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  private MockHttpServletRequest agentRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/v1/enroll");
    request.setRequestURI("/agent/v1/enroll");
    request.setRemoteAddr("192.168.0.50");
    return request;
  }

  @Test
  void plainHttpFromTheNetworkIsRefused() throws Exception {
    assertThat(run(agentRequest()).getStatus()).isEqualTo(426);
  }

  @Test
  void tlsPassesThrough() throws Exception {
    MockHttpServletRequest request = agentRequest();
    request.setSecure(true);
    assertThat(run(request).getStatus()).isEqualTo(200);
  }

  @Test
  void proxyTerminatedTlsPassesThrough() throws Exception {
    MockHttpServletRequest request = agentRequest();
    request.addHeader("X-Forwarded-Proto", "https");
    assertThat(run(request).getStatus()).isEqualTo(200);
  }

  @Test
  void loopbackIsAllowedLikeTheExtensionVault() throws Exception {
    MockHttpServletRequest request = agentRequest();
    request.setRemoteAddr("127.0.0.1");
    assertThat(run(request).getStatus()).isEqualTo(200);
  }

  @Test
  void otherApiPathsAreNotTouched() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/workflows");
    request.setRequestURI("/api/workflows");
    request.setRemoteAddr("192.168.0.50");
    assertThat(run(request).getStatus()).isEqualTo(200);
  }
}

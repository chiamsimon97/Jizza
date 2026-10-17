package com.jizza.engine.connectors.egress;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.EgressBlockedException;
import com.jizza.engine.connectors.config.ConnectorProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultEgressGuardTest {

  private ConnectorProperties props;
  private DefaultEgressGuard guard;

  @BeforeEach
  void setUp() {
    props = new ConnectorProperties();
    guard = new DefaultEgressGuard(props);
  }

  private void check(String url) { guard.check(URI.create(url), null); }

  @Test
  void blocksTheCloudMetadataEndpoint() {
    assertThatThrownBy(() -> check("https://169.254.169.254/latest/meta-data/"))
        .isInstanceOf(EgressBlockedException.class)
        .hasMessageContaining("non-public");
  }

  @Test
  void blocksLoopback() {
    assertThatThrownBy(() -> check("https://127.0.0.1/admin"))
        .isInstanceOf(EgressBlockedException.class);
    assertThatThrownBy(() -> check("https://[::1]/admin"))
        .isInstanceOf(EgressBlockedException.class);
  }

  @Test
  void blocksPrivateRanges() {
    for (String host : List.of("10.0.0.5", "172.16.4.2", "192.168.1.1")) {
      assertThatThrownBy(() -> check("https://" + host + "/internal"))
          .as(host).isInstanceOf(EgressBlockedException.class);
    }
  }

  @Test
  void blocksCarrierGradeNatAndReservedRanges() {
    assertThatThrownBy(() -> check("https://100.64.0.1/x")).isInstanceOf(EgressBlockedException.class);
    assertThatThrownBy(() -> check("https://0.0.0.1/x")).isInstanceOf(EgressBlockedException.class);
    assertThatThrownBy(() -> check("https://240.0.0.1/x")).isInstanceOf(EgressBlockedException.class);
  }

  @Test
  void blocksUniqueLocalIpv6AndIpv4MappedPrivateAddresses() {
    assertThatThrownBy(() -> check("https://[fd00::1]/x")).isInstanceOf(EgressBlockedException.class);
    assertThatThrownBy(() -> check("https://[::ffff:169.254.169.254]/x"))
        .isInstanceOf(EgressBlockedException.class);
    assertThatThrownBy(() -> check("https://[::ffff:10.0.0.1]/x"))
        .isInstanceOf(EgressBlockedException.class);
  }

  @Test
  void blocksPlainHttpByDefaultAndAllowsItOnlyWhenConfigured() {
    assertThatThrownBy(() -> check("http://93.184.216.34/x"))
        .isInstanceOf(EgressBlockedException.class)
        .hasMessageContaining("Only https");

    props.getEgress().setAllowPlainHttp(true);
    assertThatCode(() -> check("http://93.184.216.34/x")).doesNotThrowAnyException();
  }

  @Test
  void blocksSchemesThatAreNotHttp() {
    assertThatThrownBy(() -> check("file:///etc/passwd")).isInstanceOf(EgressBlockedException.class);
    assertThatThrownBy(() -> check("gopher://93.184.216.34/x"))
        .isInstanceOf(EgressBlockedException.class);
  }

  @Test
  void allowsAPubliclyRoutableHttpsTarget() {
    assertThatCode(() -> check("https://93.184.216.34/v1/me")).doesNotThrowAnyException();
  }

  @Test
  void confinesAToolToTheHostItsConnectionDeclared() {
    // Exempt from the address rule so this test exercises the declared-host rule alone, without
    // depending on what these names resolve to from wherever it happens to run.
    props.getEgress().setAllowHosts(List.of("api.example.com", "eu.api.example.com"));

    assertThatCode(() -> guard.check(URI.create("https://api.example.com/v1/me"), "api.example.com"))
        .doesNotThrowAnyException();
    assertThatCode(() -> guard.check(URI.create("https://eu.api.example.com/v1/me"),
        "https://api.example.com/base")).doesNotThrowAnyException();

    assertThatThrownBy(() -> guard.check(URI.create("https://attacker.test/steal"), "api.example.com"))
        .isInstanceOf(EgressBlockedException.class)
        .hasMessageContaining("declared for api.example.com");
  }

  @Test
  void refusesAHostThatDoesNotResolveRatherThanTryingIt() {
    assertThatThrownBy(() -> check("https://this-host-does-not-exist.invalid/x"))
        .isInstanceOf(EgressBlockedException.class)
        .hasMessageContaining("does not resolve");
  }

  @Test
  void refusesATargetWithoutAHost() {
    assertThatThrownBy(() -> guard.check(URI.create("/relative/path"), null))
        .isInstanceOf(EgressBlockedException.class);
    assertThatThrownBy(() -> guard.check(null, null)).isInstanceOf(EgressBlockedException.class);
  }

  @Test
  void anExplicitlyAllowedHostIsExemptForLocalDevelopment() {
    assertThatThrownBy(() -> check("https://localhost:9000/mock"))
        .isInstanceOf(EgressBlockedException.class);

    props.getEgress().setAllowHosts(List.of("localhost"));
    assertThatCode(() -> check("https://localhost:9000/mock")).doesNotThrowAnyException();
  }
}

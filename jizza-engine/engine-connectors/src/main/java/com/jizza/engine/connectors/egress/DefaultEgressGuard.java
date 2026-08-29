package com.jizza.engine.connectors.egress;

import com.jizza.engine.api.connector.EgressGuard;
import com.jizza.engine.api.error.EgressBlockedException;
import com.jizza.engine.connectors.config.ConnectorProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * The SSRF and egress guard (R8). Every outbound call the platform makes on the user's behalf --
 * OAuth token exchanges as well as generated tool calls -- goes through here first.
 *
 * <p>Four rules, applied in order:
 * <ol>
 *   <li>the scheme must be https (plain http only where explicitly enabled for development);</li>
 *   <li>the host must resolve, and <em>every</em> address it resolves to must be publicly
 *       routable -- loopback, link-local, site-local, unique-local, CGNAT, multicast and the cloud
 *       metadata address are all refused;</li>
 *   <li>where the connection declares a host, the target must be that host or a subdomain of it,
 *       so a tool cannot be pointed somewhere the user never connected;</li>
 *   <li>anything that cannot be resolved or parsed is refused rather than attempted.</li>
 * </ol>
 *
 * <p>DNS is resolved here and the result is what is checked, which is what closes the
 * "resolves to a public address, then to 169.254.169.254" rebinding gap for the check itself.
 */
@Component
public class DefaultEgressGuard implements EgressGuard {

  /** The cloud instance-metadata address, the classic SSRF target. */
  private static final String METADATA_IPV4 = "169.254.169.254";

  private final ConnectorProperties props;

  public DefaultEgressGuard(ConnectorProperties props) {
    this.props = props;
  }

  @Override
  public void check(URI target, String declaredHost) {
    if (target == null || target.getHost() == null) {
      throw new EgressBlockedException("The request target is not an absolute URL with a host");
    }
    String host = target.getHost().toLowerCase(Locale.ROOT);
    String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);

    if (!scheme.equals("https") && !(scheme.equals("http") && plainHttpAllowedFor(host))) {
      throw new EgressBlockedException(
          "Only https targets are permitted; refused " + scheme + "://" + host);
    }

    if (declaredHost != null && !declaredHost.isBlank()) {
      String declared = normaliseHost(declaredHost);
      if (!host.equals(declared) && !host.endsWith("." + declared)) {
        throw new EgressBlockedException("This connection is declared for " + declared
            + "; refused a request to " + host);
      }
    }

    if (isExempt(host)) return;

    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      throw new EgressBlockedException("Refused " + host + ": the host does not resolve");
    }
    if (addresses.length == 0) {
      throw new EgressBlockedException("Refused " + host + ": the host resolves to no address");
    }
    for (InetAddress address : addresses) {
      if (!isPubliclyRoutable(address)) {
        throw new EgressBlockedException("Refused " + host + ": it resolves to the non-public "
            + "address " + address.getHostAddress());
      }
    }
  }

  private boolean plainHttpAllowedFor(String host) {
    return props.getEgress().isAllowPlainHttp() || isExempt(host);
  }

  private boolean isExempt(String host) {
    if (props.getEgress().isAllowPrivateNetworks()) return true;
    return props.getEgress().getAllowHosts().stream()
        .anyMatch(allowed -> allowed.equalsIgnoreCase(host));
  }

  boolean isPubliclyRoutable(InetAddress address) {
    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()
        || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
      return false;
    }
    if (METADATA_IPV4.equals(address.getHostAddress())) return false;

    byte[] bytes = address.getAddress();
    if (bytes.length == 4) {
      int first = bytes[0] & 0xFF;
      int second = bytes[1] & 0xFF;
      // 100.64.0.0/10 carrier-grade NAT: not private per InetAddress, not publicly routable either.
      if (first == 100 && second >= 64 && second <= 127) return false;
      // 0.0.0.0/8 "this network" and 240.0.0.0/4 reserved.
      if (first == 0 || first >= 240) return false;
    } else if (bytes.length == 16) {
      // fc00::/7 unique local addresses.
      if ((bytes[0] & 0xFE) == 0xFC) return false;
      // IPv4-mapped IPv6 hides a v4 address behind a v6 shape; re-check the embedded address.
      if (isIpv4Mapped(bytes)) {
        byte[] embedded = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
        try {
          return isPubliclyRoutable(InetAddress.getByAddress(embedded));
        } catch (UnknownHostException e) {
          return false;
        }
      }
    }
    return true;
  }

  private boolean isIpv4Mapped(byte[] bytes) {
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) return false;
    }
    return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
  }

  private String normaliseHost(String hostOrUrl) {
    String value = hostOrUrl.trim().toLowerCase(Locale.ROOT);
    if (value.contains("://")) {
      try {
        String host = URI.create(value).getHost();
        if (host != null) return host;
      } catch (IllegalArgumentException ignored) {
        // Fall through and treat the configured value as a bare host.
      }
    }
    int slash = value.indexOf('/');
    if (slash > 0) value = value.substring(0, slash);
    int colon = value.indexOf(':');
    if (colon > 0) value = value.substring(0, colon);
    return value;
  }
}

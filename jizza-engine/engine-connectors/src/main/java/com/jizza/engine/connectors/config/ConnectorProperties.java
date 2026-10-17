package com.jizza.engine.connectors.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jizza.connectors")
public class ConnectorProperties {

  /**
   * Public base URL the OAuth provider will redirect back to. Local-first still needs this to be
   * reachable from the internet, so in development it is a tunnel (ngrok/cloudflared) or a provider
   * that permits {@code http://localhost}. Config, never a literal, so hosting later is a value
   * change.
   */
  private String callbackBaseUrl = "http://localhost:8080";

  private String callbackPath = "/api/oauth/callback";

  /** How long an authorization attempt may stay open before its state expires. */
  private Duration stateTtl = Duration.ofMinutes(10);

  /** Refresh this long before the access token actually expires. */
  private Duration refreshSkew = Duration.ofMinutes(2);

  private final Http http = new Http();
  private final Egress egress = new Egress();

  public static class Http {
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(30);
    /** Hard cap on a response body, so one endpoint cannot exhaust memory. */
    private long maxResponseBytes = 2 * 1024 * 1024;

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration v) { this.connectTimeout = v; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration v) { this.readTimeout = v; }
    public long getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(long v) { this.maxResponseBytes = v; }
  }

  public static class Egress {
    /**
     * Permit private, loopback and link-local targets. Off by default and intended only for a
     * developer pointing at a mock service on their own machine.
     */
    private boolean allowPrivateNetworks = false;

    /** Hosts exempted from the private-address rule when the above is off, e.g. a local mock. */
    private List<String> allowHosts = new ArrayList<>();

    /** Permit plain http. Off by default: connector traffic carries live credentials. */
    private boolean allowPlainHttp = false;

    public boolean isAllowPrivateNetworks() { return allowPrivateNetworks; }
    public void setAllowPrivateNetworks(boolean v) { this.allowPrivateNetworks = v; }
    public List<String> getAllowHosts() { return allowHosts; }
    public void setAllowHosts(List<String> v) { this.allowHosts = v; }
    public boolean isAllowPlainHttp() { return allowPlainHttp; }
    public void setAllowPlainHttp(boolean v) { this.allowPlainHttp = v; }
  }

  public String redirectUri() {
    String base = callbackBaseUrl.endsWith("/")
        ? callbackBaseUrl.substring(0, callbackBaseUrl.length() - 1) : callbackBaseUrl;
    return base + callbackPath;
  }

  public String getCallbackBaseUrl() { return callbackBaseUrl; }
  public void setCallbackBaseUrl(String v) { this.callbackBaseUrl = v; }
  public String getCallbackPath() { return callbackPath; }
  public void setCallbackPath(String v) { this.callbackPath = v; }
  public Duration getStateTtl() { return stateTtl; }
  public void setStateTtl(Duration v) { this.stateTtl = v; }
  public Duration getRefreshSkew() { return refreshSkew; }
  public void setRefreshSkew(Duration v) { this.refreshSkew = v; }
  public Http getHttp() { return http; }
  public Egress getEgress() { return egress; }
}

package com.jizza.engine.security.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All security knobs are configuration; nothing sensitive is ever a literal in the source (R57). */
@ConfigurationProperties(prefix = "jizza.security")
public class SecurityProperties {

  /** Path to the key file holding the master key(s). Preferred for local-first runs. */
  private String masterKeyFile;

  /** PKCS12 keystore holding the master key — the OS keystore option. */
  private String keystorePath;
  private String keystorePassword;
  private String keystoreAlias = "jizza-master";

  /** Dev-only fallback: a base64 master key supplied through the environment. */
  private String masterKey;

  /** Refuse to start with an environment-supplied key outside development. */
  private boolean allowEnvMasterKey = true;

  private final Session session = new Session();
  private final StepUp stepUp = new StepUp();
  private final Lockout lockout = new Lockout();
  private final Totp totp = new Totp();
  private final Cors cors = new Cors();
  private final Mfa mfa = new Mfa();
  private final Registration registration = new Registration();

  public static class Session {
    private Duration idleTimeout = Duration.ofMinutes(30);
    private Duration absoluteTimeout = Duration.ofHours(12);
    private String cookieName = "JIZZA_SESSION";
    private boolean cookieSecure = true;
    /** Strict: the session cookie is never sent on a request another site started. */
    private String sameSite = "Strict";

    public Duration getIdleTimeout() { return idleTimeout; }
    public void setIdleTimeout(Duration v) { this.idleTimeout = v; }
    public Duration getAbsoluteTimeout() { return absoluteTimeout; }
    public void setAbsoluteTimeout(Duration v) { this.absoluteTimeout = v; }
    public String getCookieName() { return cookieName; }
    public void setCookieName(String v) { this.cookieName = v; }
    public boolean isCookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean v) { this.cookieSecure = v; }
    public String getSameSite() { return sameSite; }
    public void setSameSite(String v) { this.sameSite = v; }
  }

  public static class StepUp {
    /** How recently the user must have re-authenticated for a high-risk route to proceed. */
    private Duration freshness = Duration.ofMinutes(5);
    /**
     * Wrong step-up answers a session may give in a row before it is revoked. Someone holding a
     * stolen session cookie cannot sit and guess the password behind it.
     */
    private int maxFailures = 5;
    public Duration getFreshness() { return freshness; }
    public void setFreshness(Duration v) { this.freshness = v; }
    public int getMaxFailures() { return maxFailures; }
    public void setMaxFailures(int v) { this.maxFailures = v; }
  }

  public static class Mfa {
    /**
     * Every account must use two-step sign-in. Until it is set up, a session can reach only the
     * setup endpoints. Jizza holds memory, personal data and live credentials, so this is on.
     */
    private boolean required = true;
    /** How many one-time recovery codes are issued at a time. */
    private int recoveryCodes = 8;
    public boolean isRequired() { return required; }
    public void setRequired(boolean v) { this.required = v; }
    public int getRecoveryCodes() { return recoveryCodes; }
    public void setRecoveryCodes(int v) { this.recoveryCodes = v; }
  }

  public static class Registration {
    /**
     * Jizza is single-user: once the owner's account exists, nobody else can register. Turn this
     * on only for a deliberately shared instance or a test suite.
     */
    private boolean allowAdditionalUsers = false;
    public boolean isAllowAdditionalUsers() { return allowAdditionalUsers; }
    public void setAllowAdditionalUsers(boolean v) { this.allowAdditionalUsers = v; }
  }

  public static class Lockout {
    private int maxFailedAttempts = 5;
    private Duration lockoutDuration = Duration.ofMinutes(15);
    private int authRequestsPerMinute = 20;

    public int getMaxFailedAttempts() { return maxFailedAttempts; }
    public void setMaxFailedAttempts(int v) { this.maxFailedAttempts = v; }
    public Duration getLockoutDuration() { return lockoutDuration; }
    public void setLockoutDuration(Duration v) { this.lockoutDuration = v; }
    public int getAuthRequestsPerMinute() { return authRequestsPerMinute; }
    public void setAuthRequestsPerMinute(int v) { this.authRequestsPerMinute = v; }
  }

  public static class Totp {
    private String issuer = "Jizza";
    private int digits = 6;
    private int periodSeconds = 30;
    /** Number of ±steps accepted, absorbing clock drift. */
    private int skewSteps = 1;

    public String getIssuer() { return issuer; }
    public void setIssuer(String v) { this.issuer = v; }
    public int getDigits() { return digits; }
    public void setDigits(int v) { this.digits = v; }
    public int getPeriodSeconds() { return periodSeconds; }
    public void setPeriodSeconds(int v) { this.periodSeconds = v; }
    public int getSkewSteps() { return skewSteps; }
    public void setSkewSteps(int v) { this.skewSteps = v; }
  }

  public static class Cors {
    /** Browser origins allowed to call the API. Empty means same-origin only. */
    private java.util.List<String> allowedOrigins = new java.util.ArrayList<>(
        java.util.List.of("http://localhost:5173"));
    public java.util.List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(java.util.List<String> v) { this.allowedOrigins = v; }
  }

  public String getMasterKeyFile() { return masterKeyFile; }
  public void setMasterKeyFile(String v) { this.masterKeyFile = v; }
  public String getKeystorePath() { return keystorePath; }
  public void setKeystorePath(String v) { this.keystorePath = v; }
  public String getKeystorePassword() { return keystorePassword; }
  public void setKeystorePassword(String v) { this.keystorePassword = v; }
  public String getKeystoreAlias() { return keystoreAlias; }
  public void setKeystoreAlias(String v) { this.keystoreAlias = v; }
  public String getMasterKey() { return masterKey; }
  public void setMasterKey(String v) { this.masterKey = v; }
  public boolean isAllowEnvMasterKey() { return allowEnvMasterKey; }
  public void setAllowEnvMasterKey(boolean v) { this.allowEnvMasterKey = v; }
  public Session getSession() { return session; }
  public StepUp getStepUp() { return stepUp; }
  public Lockout getLockout() { return lockout; }
  public Totp getTotp() { return totp; }
  public Cors getCors() { return cors; }
  public Mfa getMfa() { return mfa; }
  public Registration getRegistration() { return registration; }
}

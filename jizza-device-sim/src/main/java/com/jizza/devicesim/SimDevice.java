package com.jizza.devicesim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A fake Jizza Agent app speaking agent protocol v1, for tests and for trying the server by hand.
 *
 * <p>It does what every real app must: keeps an Ed25519 key, enrolls with a pairing code or an
 * enrollment token, signs a challenge for a short-lived token, says hello, sends its manifest,
 * checks the server's signature, deadline and nonce on every command before running it, and
 * answers accepted then result. What a command does is up to the handlers the caller registers.
 *
 * <pre>{@code
 * SimDevice device = new SimDevice("http://localhost:8081");
 * device.handle("system.info", input -> device.json().createObjectNode().put("os", "Sim"));
 * device.enrollWithCode("123456", "Test laptop", "windows");
 * device.signIn();
 * device.hello();
 * device.sendManifest();
 * device.pollOnce(25);   // or device.connect() for the WebSocket
 * }</pre>
 */
public final class SimDevice implements AutoCloseable {

  /** What a handler does when the command should fail rather than succeed. */
  public static final class Failure extends RuntimeException {
    public final String code;
    public final JsonNode output;

    public Failure(String code, String message) {
      this(code, message, null);
    }

    public Failure(String code, String message, JsonNode output) {
      super(message);
      this.code = code;
      this.output = output;
    }
  }

  private final URI base;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ObjectMapper json = new ObjectMapper();
  private final KeyPair keys;
  private final boolean p256;
  private final Map<String, Function<JsonNode, JsonNode>> handlers = new LinkedHashMap<>();
  private final ObjectNode capabilities = json.createObjectNode();
  private final ArrayNode tools = json.createArrayNode();
  private final ObjectNode items = json.createObjectNode();
  private final List<JsonNode> received = new CopyOnWriteArrayList<>();
  private final List<String> seenNonces = Collections.synchronizedList(new ArrayList<>());

  private UUID deviceId;
  private PublicKey serverKey;
  private String serverKeyText;
  private String token;
  private WebSocket socket;
  private boolean autoRun = true;
  private String keyStorage;
  private String origin;

  public SimDevice(String baseUrl) {
    this(baseUrl, false);
  }

  /**
   * @param p256 use an ECDSA P-256 key, as a phone's Secure Enclave or Android Keystore makes,
   *             instead of Ed25519
   */
  public SimDevice(String baseUrl, boolean p256) {
    this.base = URI.create(baseUrl.replaceAll("/+$", "") + "/");
    this.p256 = p256;
    try {
      if (p256) {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        this.keys = generator.generateKeyPair();
      } else {
        this.keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("This Java has no " + (p256 ? "P-256" : "Ed25519"), e);
    }
    handle("system.ping", input -> json.createObjectNode().put("pong", true));
  }

  public ObjectMapper json() { return json; }

  public UUID deviceId() { return deviceId; }

  public String token() { return token; }

  /** Every message the server sent, in order. */
  public List<JsonNode> received() { return received; }

  /** Turns off running commands as they arrive, to test what the server does when nobody answers. */
  public SimDevice autoRun(boolean on) {
    this.autoRun = on;
    return this;
  }

  /** Uses the key-storage name a real platform sends during enrollment. */
  public SimDevice keyStorage(String storage) {
    this.keyStorage = storage;
    return this;
  }

  /**
   * Sends this {@code Origin} header on every request and on the WebSocket handshake, as a
   * browser extension does (for example {@code chrome-extension://<id>}).
   */
  public SimDevice origin(String origin) {
    this.origin = origin;
    return this;
  }

  /** Declares an action (as {@code name} or {@code name@version}) and what running it returns. */
  public SimDevice handle(String action, Function<JsonNode, JsonNode> handler) {
    handlers.put(action, handler);
    return this;
  }

  /** A capability and its local limits, sent in the manifest. */
  public SimDevice capability(String name, ObjectNode limits) {
    capabilities.set(name, limits == null ? json.createObjectNode() : limits);
    return this;
  }

  /**
   * A tool of the app's own, described in the manifest so workflows and chat can use it:
   * {@code {"action":"x-acme.export","version":1,"title","input":{JSON Schema},"output"?}}.
   */
  public SimDevice tool(ObjectNode declaration, Function<JsonNode, JsonNode> handler) {
    tools.add(declaration);
    handlers.put(declaration.path("action").asText(), handler);
    return this;
  }

  /** Saved items of one kind (recordings, shortcuts, apps), as {@code [{"id","name"}]}. */
  public SimDevice items(String kind, ArrayNode list) {
    items.set(kind, list);
    return this;
  }

  // ── Enrolling and signing in ───────────────────────────────────────────────

  public JsonNode enrollWithCode(String code, String name, String platform) {
    return enroll("code", code, name, platform);
  }

  public JsonNode enrollWithToken(String enrollmentToken, String name, String platform) {
    return enroll("enrollmentToken", enrollmentToken, name, platform);
  }

  private JsonNode enroll(String field, String secret, String name, String platform) {
    ObjectNode body = json.createObjectNode();
    body.put(field, secret);
    body.put("publicKey", (p256 ? "p256:" : "ed25519:")
        + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
    body.put("keyStorage", keyStorage == null ? (p256 ? "secure_enclave" : "file") : keyStorage);
    body.put("name", name);
    body.put("platform", platform);
    body.put("osVersion", "sim-1");
    body.put("appVersion", "0.0.1-sim");
    body.putArray("protocol").add(1);
    JsonNode enrolled = postOk("agent/v1/enroll", body, false);
    deviceId = UUID.fromString(enrolled.path("deviceId").asText());
    serverKeyText = enrolled.path("serverSigningKey").asText();
    serverKey = parseKey(serverKeyText);
    return enrolled;
  }

  /** Asks for a challenge, signs it, and keeps the token it is given. */
  public String signIn() {
    JsonNode challenge = postOk("agent/v1/auth/challenge",
        json.createObjectNode().put("deviceId", deviceId.toString()), false);
    String nonce = challenge.path("nonce").asText();
    JsonNode issued = postOk("agent/v1/auth/token", json.createObjectNode()
        .put("deviceId", deviceId.toString()).put("nonce", nonce).put("signature", sign(nonce)), false);
    token = issued.path("token").asText();
    return token;
  }

  // ── Messages ───────────────────────────────────────────────────────────────

  public ObjectNode envelope(String type, JsonNode body) {
    ObjectNode env = json.createObjectNode();
    env.put("v", 1);
    env.put("type", type);
    env.put("id", UUID.randomUUID().toString());
    env.put("deviceId", deviceId.toString());
    env.put("ts", Instant.now().toString());
    env.set("body", body == null ? json.createObjectNode() : body);
    return env;
  }

  /** Sends one message over HTTPS, or the socket when connected, and returns the server's reply. */
  public JsonNode send(String type, JsonNode body) {
    ObjectNode env = envelope(type, body);
    if (socket != null) {
      String id = env.get("id").asText();
      socket.sendText(write(env), true).join();
      return awaitMessage(m -> id.equals(m.path("replyTo").asText(null)), Duration.ofSeconds(5));
    }
    return postOk("agent/v1/messages", env, true);
  }

  public JsonNode hello() {
    ObjectNode body = json.createObjectNode();
    body.putArray("protocol").add(1);
    body.put("appVersion", "0.0.1-sim");
    body.put("osVersion", "sim-1");
    if (socket != null) body.put("token", token);
    return send("hello", body);
  }

  public JsonNode sendManifest() {
    ObjectNode body = json.createObjectNode();
    ArrayNode actions = body.putArray("actions");
    handlers.keySet().forEach(actions::add);
    body.set("capabilities", capabilities);
    if (!tools.isEmpty()) body.set("tools", tools);
    if (!items.isEmpty()) body.set("items", items);
    return send("manifest", body);
  }

  public JsonNode event(String event, JsonNode data) {
    ObjectNode body = json.createObjectNode().put("name", event);
    body.set("data", data == null ? json.createObjectNode() : data);
    return send("event", body);
  }

  /** Long-polls once and handles what comes back; returns the messages. */
  public List<JsonNode> pollOnce(int waitSeconds) {
    JsonNode polled = request("GET", "agent/v1/commands?wait=" + waitSeconds, null, true);
    List<JsonNode> messages = new ArrayList<>();
    polled.path("messages").forEach(messages::add);
    for (JsonNode m : messages) onMessage(m);
    return messages;
  }

  /** Uploads a result file for a command and returns its id. */
  public String upload(UUID commandId, String name, byte[] content) {
    HttpRequest.Builder req = HttpRequest.newBuilder(base.resolve("agent/v1/files?commandId=" + commandId
            + "&name=" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8)))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/octet-stream")
        .PUT(HttpRequest.BodyPublishers.ofByteArray(content));
    if (origin != null) req.header("Origin", origin);
    return read(exchange(req.build())).path("fileId").asText();
  }

  // ── WebSocket ──────────────────────────────────────────────────────────────

  /** Opens the socket with the token in the header, then says hello and sends the manifest. */
  public void connect() {
    String ws = base.toString().replaceFirst("^http", "ws") + "agent/v1/ws";
    WebSocket.Builder builder = http.newWebSocketBuilder()
        .header("Authorization", "Bearer " + token);
    if (origin != null) builder.header("Origin", origin);
    socket = builder.buildAsync(URI.create(ws), new Listener()).join();
    hello();
    sendManifest();
  }

  public boolean connected() {
    return socket != null && !socket.isInputClosed();
  }

  public void disconnect() {
    if (socket != null) {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").orTimeout(2, TimeUnit.SECONDS)
          .exceptionally(e -> null).join();
      socket = null;
    }
  }

  private final class Listener implements WebSocket.Listener {
    private final StringBuilder buffer = new StringBuilder();

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
      buffer.append(data);
      if (last) {
        String text = buffer.toString();
        buffer.setLength(0);
        JsonNode message = read(text);
        // Off the socket's thread, so a handler may send while this listener keeps reading.
        CompletableFuture.runAsync(() -> onMessage(message));
      }
      ws.request(1);
      return null;
    }
  }

  /** Waits for a message from the server that matches. */
  public JsonNode awaitMessage(Predicate<JsonNode> match, Duration wait) {
    long until = System.nanoTime() + wait.toNanos();
    while (System.nanoTime() < until) {
      for (JsonNode m : received) if (match.test(m)) return m;
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return null;
  }

  // ── Running commands ───────────────────────────────────────────────────────

  private void onMessage(JsonNode message) {
    received.add(message);
    if ("command".equals(message.path("type").asText()) && autoRun) run(message);
  }

  /** Checks a command as a real app must, then runs it with its handler. */
  public void run(JsonNode command) {
    JsonNode body = command.path("body");
    String commandId = body.path("commandId").asText();
    String problem = check(command);
    if (problem != null) {
      send("rejected", json.createObjectNode().put("commandId", commandId)
          .put("code", problem.split(":")[0]).put("message", problem));
      return;
    }
    String action = body.path("action").asText();
    Function<JsonNode, JsonNode> handler = handlers.get(action);
    if (handler == null) handler = handlers.get(action + "@" + body.path("version").asInt(1));
    if (handler == null) {
      send("rejected", json.createObjectNode().put("commandId", commandId)
          .put("code", "unsupported").put("message", "unsupported: " + action));
      return;
    }
    send("accepted", json.createObjectNode().put("commandId", commandId));
    ObjectNode result = json.createObjectNode().put("commandId", commandId);
    try {
      JsonNode output = handler.apply(body.path("input"));
      result.put("status", "succeeded");
      result.set("output", output == null ? json.createObjectNode() : output);
    } catch (Failure f) {
      result.put("status", "failed").put("code", f.code).put("message", f.getMessage());
      if (f.output != null) result.set("output", f.output);
    } catch (RuntimeException e) {
      result.put("status", "failed").put("code", "failed").put("message", String.valueOf(e.getMessage()));
    }
    send("result", result);
  }

  /** Null when the command is genuine, for this device, fresh and not seen before. */
  String check(JsonNode command) {
    JsonNode body = command.path("body");
    if (!verify(Canonical.of(body), command.path("sig").asText(null))) {
      return "bad_signature: the server's signature does not match";
    }
    if (!deviceId.toString().equals(body.path("deviceId").asText())) {
      return "bad_signature: the command is for another device";
    }
    try {
      if (Instant.parse(body.path("deadline").asText()).isBefore(Instant.now())) {
        return "expired: the command's deadline has passed";
      }
    } catch (RuntimeException e) {
      return "bad_message: the command has no deadline";
    }
    String nonce = body.path("nonce").asText();
    synchronized (seenNonces) {
      if (seenNonces.contains(nonce)) return "bad_signature: the command was replayed";
      seenNonces.add(nonce);
    }
    return null;
  }

  // ── Crypto and HTTP ────────────────────────────────────────────────────────

  /**
   * What a real app compares with the {@code fp} of the pairing QR code before trusting the key:
   * base64url SHA-256 of the server key's X.509 bytes, without padding.
   */
  public String serverKeyFingerprint() {
    try {
      byte[] der = Base64.getDecoder().decode(serverKeyText.substring("ed25519:".length()));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(
          java.security.MessageDigest.getInstance("SHA-256").digest(der));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public String sign(String text) {
    try {
      Signature signer = Signature.getInstance(p256 ? "SHA256withECDSA" : "Ed25519");
      signer.initSign(keys.getPrivate());
      signer.update(text.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(signer.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  boolean verify(String text, String signature) {
    if (signature == null || serverKey == null) return false;
    try {
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(serverKey);
      verifier.update(text.getBytes(StandardCharsets.UTF_8));
      String raw = signature.startsWith("ed25519:") ? signature.substring(8) : signature;
      return verifier.verify(Base64.getDecoder().decode(raw));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }

  private static PublicKey parseKey(String encoded) {
    try {
      byte[] der = Base64.getDecoder().decode(encoded.substring("ed25519:".length()));
      return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    } catch (GeneralSecurityException | RuntimeException e) {
      throw new IllegalStateException("The server's signing key could not be read: " + encoded, e);
    }
  }

  private JsonNode postOk(String path, JsonNode body, boolean authenticated) {
    return request("POST", path, body, authenticated);
  }

  private JsonNode request(String method, String path, JsonNode body, boolean authenticated) {
    HttpRequest.Builder req = HttpRequest.newBuilder(base.resolve(path))
        .timeout(Duration.ofSeconds(60))
        .header("Accept", "application/json");
    if (origin != null) req.header("Origin", origin);
    if (authenticated) req.header("Authorization", "Bearer " + token);
    if (body != null) {
      req.header("Content-Type", "application/json");
      req.method(method, HttpRequest.BodyPublishers.ofString(write(body)));
    } else {
      req.method(method, HttpRequest.BodyPublishers.noBody());
    }
    return read(exchange(req.build()));
  }

  private String exchange(HttpRequest req) {
    try {
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() >= 300) {
        throw new SimException(res.statusCode(), res.body());
      }
      return res.body();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** The server answered with an error status. */
  public static final class SimException extends RuntimeException {
    public final int status;
    public final String body;

    SimException(int status, String body) {
      super(status + " " + body);
      this.status = status;
      this.body = body;
    }
  }

  private String write(JsonNode node) {
    try {
      return json.writeValueAsString(node);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode read(String text) {
    try {
      return text == null || text.isBlank() ? json.createObjectNode() : json.readTree(text);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() {
    disconnect();
  }

  /**
   * Pairs with a running server and answers commands until stopped:
   * {@code java -cp ... com.jizza.devicesim.SimDevice http://localhost:8081 123456}.
   */
  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("usage: SimDevice <server-url> <pairing-code> [name] [--browser]");
      System.exit(2);
    }
    boolean browser = java.util.Arrays.asList(args).contains("--browser");
    SimDevice device = new SimDevice(args[0]);
    device.handle("system.info", input -> device.json().createObjectNode()
        .put("os", "Simulator").put("hostname", "sim").put("user", System.getProperty("user.name")));
    device.handle("notify.show", input -> device.json().createObjectNode().put("shown", true));
    device.capability("system", null).capability("notify", null);
    if (browser) new FakeBrowser(device);
    String name = args.length > 2 && !args[2].startsWith("--") ? args[2]
        : browser ? "Simulated browser" : "Simulated device";
    device.keyStorage(browser ? "browser" : "file");
    device.enrollWithCode(args[1], name, browser ? "browser" : "linux");
    System.out.println("Paired as " + device.deviceId());
    device.signIn();
    device.connect();
    System.out.println("Connected; waiting for commands. Ctrl-C to stop.");
    while (true) {
      Thread.sleep(Duration.ofMinutes(10).toMillis());
      device.disconnect();
      device.signIn();
      device.connect();
    }
  }
}

package com.jizza.engine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.device.DeviceCommandDto;
import com.jizza.engine.api.device.DeviceCommandRequest;
import com.jizza.engine.api.device.DeviceCommandStatus;
import com.jizza.engine.api.device.DeviceCommands;
import com.jizza.engine.api.device.DeviceTarget;
import com.jizza.engine.api.util.Json;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sessions with MCP servers that run on a paired desktop app (K7): a local (stdio) server the
 * person set up in the app itself. Jizza never names a program to start: it names the server as
 * the app knows it, and the app runs its own configured command under its own limits.
 *
 * <p>Each call is one device command, {@code mcp.listTools} or {@code mcp.callTool}, awaited like a
 * chat turn's device step. A server on a device that is offline reports why, plainly.
 */
public class DeviceMcpSessions implements McpSession.Factory {

  public static final String LIST_TOOLS = "mcp.listTools";
  public static final String CALL_TOOL = "mcp.callTool";

  /** Starting a local server for the first time can take a while (npx downloads it). */
  static final Duration LIST_WAIT = Duration.ofSeconds(60);
  static final Duration MIN_CALL_WAIT = Duration.ofSeconds(60);

  private final McpSession.Factory delegate;
  private final ObjectProvider<DeviceCommands> devices;
  private final ObjectProvider<PlatformTransactionManager> transactions;
  private final McpJsonMapper mapper = new JacksonMcpJsonMapper(Json.mapper());

  public DeviceMcpSessions(McpSession.Factory delegate, ObjectProvider<DeviceCommands> devices,
                           ObjectProvider<PlatformTransactionManager> transactions) {
    this.delegate = delegate;
    this.devices = devices;
    this.transactions = transactions;
  }

  /**
   * Saves the command in its own transaction, so the app's answer finds it even when the caller
   * is inside one (refreshing a server's tools is), and the wait is for the answer alone.
   */
  private DeviceCommandDto submit(DeviceCommands commands, DeviceCommandRequest request) {
    PlatformTransactionManager manager = transactions.getIfAvailable();
    if (manager == null) return commands.submit(request);
    TransactionTemplate template = new TransactionTemplate(manager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template.execute(status -> commands.submit(request));
  }

  /**
   * Waits outside the caller's transaction, so the answer is read fresh rather than from what
   * that transaction already loaded.
   */
  private DeviceCommandDto await(DeviceCommands commands, java.util.UUID id, Duration wait) {
    PlatformTransactionManager manager = transactions.getIfAvailable();
    if (manager == null) return commands.await(id, wait);
    TransactionTemplate template = new TransactionTemplate(manager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    return template.execute(status -> commands.await(id, wait));
  }

  @Override
  public McpSession open(String name, McpProperties.Server server) {
    if (server.getTransport() != McpProperties.Transport.DEVICE) return delegate.open(name, server);
    DeviceCommands commands = devices.getIfAvailable();
    if (commands == null) throw new IllegalStateException("Paired apps aren't available here");
    if (server.getDeviceId() == null || server.getDeviceServer() == null
        || server.getOwnerId() == null) {
      throw new IllegalStateException("MCP server '" + name + "' doesn't say which app it runs on");
    }
    return new DeviceSession(commands, server);
  }

  private final class DeviceSession implements McpSession {
    private final DeviceCommands commands;
    private final McpProperties.Server server;
    private McpSchema.Implementation info;

    DeviceSession(DeviceCommands commands, McpProperties.Server server) {
      this.commands = commands;
      this.server = server;
    }

    @Override
    public List<McpSchema.Tool> listTools() {
      JsonNode output = run(LIST_TOOLS, Json.obj(), LIST_WAIT);
      if (output.path("serverInfo").isObject()) {
        info = new McpSchema.Implementation(output.path("serverInfo").path("name").asText("local"),
            output.path("serverInfo").path("version").asText(""));
      }
      List<McpSchema.Tool> tools = new ArrayList<>();
      for (JsonNode tool : output.path("tools")) {
        tools.add(mapper.convertValue(tool, McpSchema.Tool.class));
      }
      return tools;
    }

    @Override
    public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
      ObjectNode input = Json.obj().put("tool", name);
      input.set("arguments", Json.toNode(arguments == null ? Map.of() : arguments));
      Duration wait = server.getRequestTimeout().compareTo(MIN_CALL_WAIT) > 0
          ? server.getRequestTimeout() : MIN_CALL_WAIT;
      JsonNode output = run(CALL_TOOL, input, wait);
      return mapper.convertValue(output, McpSchema.CallToolResult.class);
    }

    /** The app says who the server is along with its tools; ask once if not yet asked. */
    @Override
    public McpSchema.Implementation serverInfo() {
      if (info == null) {
        try {
          listTools();
        } catch (RuntimeException e) {
          return null;
        }
      }
      return info;
    }

    @Override
    public void close() {
      // The app keeps its server running between calls and stops it itself.
    }

    private JsonNode run(String action, ObjectNode input, Duration wait) {
      input.put("server", server.getDeviceServer());
      DeviceCommandDto sent = submit(commands, new DeviceCommandRequest(server.getOwnerId(),
          server.getWorkspaceId(), DeviceTarget.device(server.getDeviceId()), action, 1, input,
          wait, null, null, null, true));
      DeviceCommandDto done = await(commands, sent.id(), wait);
      if (done.status() == DeviceCommandStatus.SUCCEEDED) {
        return done.output() == null ? Json.obj() : done.output();
      }
      if (!done.status().finished()) {
        commands.cancel(done.id(), "No answer in time");
        throw new IllegalStateException("The app on " + done.deviceName() + " didn't answer in "
            + wait.toSeconds() + " seconds. Is it running and online?");
      }
      throw new IllegalStateException(done.explain());
    }
  }
}

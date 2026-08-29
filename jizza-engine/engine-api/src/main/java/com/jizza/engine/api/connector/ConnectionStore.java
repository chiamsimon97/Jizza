package com.jizza.engine.api.connector;

import com.jizza.engine.api.dto.ConnectionDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage and credential handling for connections (R23).
 *
 * <p>Deliberately policy-free. It will store whatever it is given, encrypting the client secret on
 * the way in; what counts as a valid connection, what the defaults are and what gets audited are
 * product decisions and live in the platform. The engine's own OAuth and tool-execution paths read
 * credentials through here rather than through the platform.
 */
public interface ConnectionStore {

  ConnectionDto create(UUID userId, ConnectionSettings settings);

  ConnectionDto update(UUID connectionId, ConnectionSettings settings);

  List<ConnectionDto> list(UUID userId);

  Optional<ConnectionDto> find(UUID connectionId);

  void delete(UUID connectionId);
}

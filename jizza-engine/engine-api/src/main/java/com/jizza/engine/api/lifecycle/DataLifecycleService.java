package com.jizza.engine.api.lifecycle;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

/** Export and permanent deletion of everything a user owns (R48). */
public interface DataLifecycleService {

  ObjectNode export(UUID userId);

  void hardDelete(UUID userId);
}

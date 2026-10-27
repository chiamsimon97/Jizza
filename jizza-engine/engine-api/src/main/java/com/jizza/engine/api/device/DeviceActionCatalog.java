package com.jizza.engine.api.device;

import java.util.List;
import java.util.Optional;

/** Every action device apps build to, loaded once from {@code device-actions.json}. */
public interface DeviceActionCatalog {

  List<DeviceActionSpec> all();

  /** The given version, or the newest when {@code version} is 0. */
  Optional<DeviceActionSpec> find(String action, int version);

  /** As {@link #find}, but an {@code x-} extra the catalog does not know reads as one. */
  default Optional<DeviceActionSpec> resolve(String action, int version) {
    Optional<DeviceActionSpec> known = find(action, version);
    if (known.isPresent() || action == null || !action.startsWith(DeviceActionSpec.EXTENSION_PREFIX)) {
      return known;
    }
    return Optional.of(DeviceActionSpec.unknownExtension(action, Math.max(1, version)));
  }
}

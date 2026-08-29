package com.jizza.common.autonomy;

import java.util.UUID;

/** Global autonomy pause (R40). Checked at every safe checkpoint, never bypassed. */
public interface KillSwitch {

  boolean paused(UUID userId);

  void setPaused(UUID userId, boolean paused);

  /** Throws {@link com.jizza.common.error.AutonomyDeniedException} when autonomy is paused. */
  void requireActive(UUID userId);
}

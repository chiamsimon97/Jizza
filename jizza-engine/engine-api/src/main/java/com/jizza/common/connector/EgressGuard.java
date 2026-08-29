package com.jizza.common.connector;

import java.net.URI;

/** Decides whether an outbound URL may be dialled at all (R8). */
public interface EgressGuard {

  /** @throws com.jizza.common.error.EgressBlockedException when the target is not permitted. */
  void check(URI target, String declaredHost);
}

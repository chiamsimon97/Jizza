package com.jizza.common.enums;

public enum NotificationKind { INFO, PERMISSION_REQUEST, WORKFLOW_REVIEW, INSTRUCTION_REQUIRED;
  /** Blocking kinds halt their originating task until answered (R37/R39). */
  public boolean blocking() { return this != INFO; }
}

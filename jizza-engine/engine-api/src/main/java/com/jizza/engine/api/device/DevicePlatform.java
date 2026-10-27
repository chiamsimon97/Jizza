package com.jizza.engine.api.device;

import java.util.Locale;

/** The operating system a paired device runs. Routing never depends on it; capabilities do. */
public enum DevicePlatform {
  WINDOWS, MACOS, LINUX, ANDROID, IOS, OTHER;

  /** Phones cannot hold a socket open in the background, so they are woken by a push. */
  public boolean phone() {
    return this == ANDROID || this == IOS;
  }

  /** Reads the protocol's lower-case name; anything unknown is {@link #OTHER}. */
  public static DevicePlatform from(String value) {
    if (value == null) return OTHER;
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "windows", "win32" -> WINDOWS;
      case "macos", "darwin", "mac" -> MACOS;
      case "linux", "ubuntu" -> LINUX;
      case "android" -> ANDROID;
      case "ios", "ipados" -> IOS;
      default -> OTHER;
    };
  }

  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}

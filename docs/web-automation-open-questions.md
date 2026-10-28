# Web automation open questions

This file records places where the implementation prompt and the current `release/M2_3_1` code do
not line up, plus the narrow choices made so work can continue safely.

## W0

- PRs #60 and #61 are not merged into `release/M2_3_1`. The branch has migrations only through
  V30, no saved device `items`, no Create agent dialog, and no installer configuration surface.
  W0 reserves V34 as directed so V31-V33 remain available to those pull requests. The browser
  pairing tab is a small addition to the existing Pair device dialog and shows the extension as
  "Coming soon"; it is expected to conflict with #61 and should be folded into that dialog's
  `browser-chromium` installer tab when #61 lands.
- A database already at V33 cannot be constructed from this branch because migrations V31-V33 are
  not present. The migration is tested as part of a fresh Flyway migration. CI should also run the
  same migration after #60/#61 are merged.
- The proposed data model shows `web_recording_steps.step` as `jsonb`, then requires it to be
  encrypted in the same way as `device_commands` inputs and outputs. Those command columns are
  `bytea`, so W0 uses `bytea` for recorded steps. W3 must encrypt and decrypt this column through
  `EncryptionService`; plaintext step JSON must never be persisted.
- The current pairing UI has no configured installer API. W0 cannot read
  `jizza.devices.installers.browser-chromium`, so it deliberately does not invent a second
  configuration path. The pending #61 UI must supply that configured link.
- The existing simulator always enrolled Ed25519 devices with `keyStorage: file`. W0 added an
  explicit simulator key-storage setting so `--browser` and conformance tests can accurately send
  `keyStorage: browser` without changing existing simulator defaults.

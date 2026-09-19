# HEVEL Skin Loader — S3_v0001.9

Android management app for the HEVEL POV skin library.

## SKN → ALT hierarchy

`S3_v0001.9` replaces the old flat LZR/SKN library UI with nested skin families:

```text
Turtle
  Base
  Red-Eared
  Crystal
  Laser
  Night

Military
  Base
  Desert
  Snow
  Laser
```

A top-level item is a **SKN**. Each SKN owns zero or more **ALT** variants.
Laser is now just another ALT name rather than a hard-coded bank.

The app supports:

- CREATE SKN
- ADD ALT
- rename / duplicate / delete
- reorder SKNs
- reorder ALTs inside a SKN
- MOVE ALT to another SKN
- six independent POV slots per Base or ALT
- partial Base/ALT sets
- LOAD POV
- LOAD ALT
- LOAD SKN, including all of its ALTs
- LOAD SELECTED
- LOAD ALL SKINS
- ACTIVATE Base or ALT
- loader hotspot and numeric shared-Wi-Fi IP
- phone-side PNG → trimmed RGB565+alpha `.hvs` conversion
- SHA-256 skip-unchanged sync
- raw HTTP POST uploads with fixed `Content-Length`

## Compatibility with the currently running S3_v0001.7 firmware

The phone library itself is schema 2 and fully nested.

The current S3 firmware still reads the old schema-1 `lzr` / `skn` index. To avoid corrupting that running installation, this app deliberately sends a **backward-compatible remote index**:

- `schema: 1`
- `skn: [...]` contains only top-level Base SKN IDs
- `lzr: [...]` temporarily stages ALT wire IDs so the current firmware can activate them without polluting the SKN cycle
- additive `hierarchySchema: 2` and `parents: [...]` metadata describes ALT children for the future firmware update

ALT assets are installed under unique synthetic wire IDs, so an ALT never overwrites its parent's Base files on the current server. On `S3_v0001.7`, activating an ALT uses the legacy LZR bank only as a compatibility transport; the phone still presents it as an ALT under its parent SKN.

This means the app can safely organize and upload the nested library now. The physical on-device **ALT button behavior, transient button-border timing, green-dot state, and SKN-hold Default reset still require the companion S3 firmware update** described in the approved HEVEL SKN/ALT design.

## Network

Loader AP:

```text
SSID: HEVEL_SKIN_LOADER
Password: hevelskins
Host: 192.168.4.1
```

Shared Wi-Fi uses the numeric DHCP address printed by the S3. No mDNS / `hevel-sub.local` dependency is used.

## GitHub APK build

The included workflow is:

```text
.github/workflows/S3_v0001.9.yml
```

It installs Android SDK 35 and Gradle 8.10.2, runs the source contracts, builds the debug APK, renames it to:

```text
S3_v0001.9.apk
```

and publishes that APK as a GitHub Actions artifact.

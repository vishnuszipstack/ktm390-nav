# KTM Nav

Android companion that sends Google Maps turn-by-turn guidance to the TFT of a
**KTM 390 Adventure (2022)** over the bike's MY RIDE Bluetooth link. The phone does the
navigation; the app reads Google Maps' navigation notification and forwards the next turn,
distance, road name, distance to destination and ETA to the dashboard.

Independent project. Not made by, affiliated with or endorsed by KTM or Google.

## Get the APK

Every push to `main` builds the app in GitHub Actions and publishes it as a release:
**[Latest release](https://github.com/vishnuszipstack/ktm390-nav/releases/latest)** →
`ktm390-nav.apk`. After the first install, **Settings → Updates** installs newer builds
in place (every build is signed with the same key in `signing/`).

## First ride checklist (bike parked)

1. Open the app and accept the notice.
2. **Bike tab**: allow notification access and Nearby devices.
3. On the bike, switch on the phone / MY RIDE connection in the TFT menu (needs the KTM
   MY RIDE connectivity unit). Pair the bike in Android's Bluetooth settings.
4. Choose the bike on the Bike tab and tap **Connect**.
5. **Test TFT tab**: send a text and a few turns, and mark which fields really appear.
   Fields marked *Not shown* are never sent while navigating.
6. Start a route in Google Maps. The Ride tab shows the next turn and whether it is on the bike.

No bike handy? Choose **Simulated TFT** on the Bike tab and play a route on the Test TFT tab.

## What this MVP covers

| Spec item | Where |
|---|---|
| Notification listener (Google Maps; OsmAnd/Waze opt-in, untested) | `source/NavListenerService.kt` |
| Parser: turns, roundabout exits, U-turns, exits, arrival, rerouting, distances, ETA | `source/NavNotificationParser.kt`, `nav/TextParsing.kt` |
| Provider-independent instruction model | `nav/NavigationInstruction.kt` |
| Validation, duplicate suppression, stale timeout, safe resume after reconnect | `nav/InstructionGate.kt` |
| KTM protocol adapter, isolated from parsing | `ktm/` (framing, JSON messages, icon mapping, field support) |
| Bluetooth link: one socket, serialized writes, bounded backoff, ACL-triggered retry | `link/BikeLink.kt`, `link/Bluetooth.kt` |
| Screen-off operation | `bridge/BridgeService.kt` (foreground service, type `connectedDevice`) |
| Setup wizard and compatibility report | Bike tab |
| Stationary TFT tests, route simulator | Test TFT tab, `sim/RouteSimulator.kt` |
| Diagnostics with redacted, shareable report and notification samples | Diagnostics tab, `diag/DiagLog.kt` |

Not in this build yet: phone map, ride recording/GPX, fuel and service logs, saved places
(P1 items in the spec).

## Known limits

- **The 390's protocol is unverified.** The framing (`uint32 length | 0x01 | JSON`), service
  UUID `cc4c1fb3-482e-4389-bdeb-57b7aac889ae` and field names come from community reverse
  engineering confirmed on a 790 Adventure. Use the Test TFT screen to confirm each field on
  your bike before riding with it.
- **No TFT mini-map.** The known protocol only takes fixed turn icons and short text, so a map
  on the TFT is marked unsupported. Turn-by-turn continues.
- **Google Maps wording can change.** The parser is English-only and doesn't depend on which
  notification field holds what. Unclear directions are never guessed: the last good turn
  stays up until it goes stale, then the display is cleared. If turns stop appearing, turn on
  verbose logging, ride a short route and share the samples from Diagnostics.
- Roundabout exits are drawn from the exit number (1st/2nd/3rd…), not the real exit angle.
- Some phones kill background apps. If guidance stops with the screen off, set the app's battery
  use to Unrestricted (Diagnostics links to it).

## Build locally

`./gradlew testDebugUnitTest assembleDebug` with JDK 17 and the Android SDK (platform 36).

## Safety

Set up and test only while parked. The app never talks to the ECU, ABS, traction control or
any riding setting: it only writes to the dashboard's display service.

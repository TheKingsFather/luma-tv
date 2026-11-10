# Luma TV

A native Android TV client with an iOS-inspired glass interface and Stremio QR pairing. Version **0.5.0**. Package: `tv.luma`. Android 8.0+ (API 26), landscape, D-pad remote.

Source: [TheKingsFather/luma-tv](https://github.com/TheKingsFather/luma-tv).

## New in 0.5.0

App dialogs now share one glass surface, typography, spacing and remote-focus
treatment. This includes browse/player sources, audio tracks, embedded/add-on
subtitles, sleep timer, season selection, preferred language, playback failures,
still-watching prompts, source discovery/status messages, disconnect and exit.
Current choices have a mint checkmark and receive initial focus. Long lists
scroll within the panel, with a consistent Close action and Back cancellation.

Brief feedback such as saved-library and sleep-timer messages uses a matching
glass notice that fades after four seconds without taking remote focus.
Nested player menus retain their dialog state until the active menu closes;
releasing the player dismisses its open menus.

Install with `adb install -r artifacts/Luma-TV-0.5.0.apk`.

## New in 0.4.1

The browse header uses one wider, centered glass bar with Search inside it and
five evenly spaced section tabs. The active section has a mint dot, and remote
focus highlights the whole tab. The browse header no longer repeats the Luma
logo. Settings remains available at the top right.

Install with `adb install -r artifacts/Luma-TV-0.4.1.apk`.

## New in 0.4.0

Movie and series cards expand after 350 ms of D-pad focus, keeping their artwork
height while widening into a landscape preview. Backdrop artwork, logos or title
text, year and rating appear over a dark gradient. Moving focus away collapses
the card. Horizontal rows move neighbours aside and reveal the whole preview;
catalog grids retain their positions and raise the preview within the row bounds.
Live/landscape cards retain their existing proportions and artwork treatment.

The exit prompt now uses Luma's logo, rounded teal/violet material and matching
remote-focus buttons. **Stay** is selected initially. Back dismisses the prompt;
leaving requires selecting **Exit Luma**.

The update uses the existing signing certificate and can be installed with
`adb install -r artifacts/Luma-TV-0.4.0.apk`. Signing keys and account data are
not part of this repository. See `artifacts/VALIDATION-0.4.0.txt` for build and
device-check results.

## Connect your account

1. Open **Luma TV** from the TV's apps list.
2. Scan the QR code with your phone's camera.
3. Connect on the official `link.stremio.com` page. The TV detects the connection and loads your installed add-ons and library.

You can also visit `link.stremio.com` and enter the code shown on the TV. Codes expire after ten minutes in Luma; select **New code** to renew. **Explore the interface** browses the public Cinemeta catalog before connecting.

Manage provider installation/configuration in Stremio on your phone, then choose **Settings → Refresh Stremio** on the TV. Disconnecting removes this app's account data from the TV.

## Changes carried over from Fedora (0.3.0)

- Live only includes declared channel, TV, IPTV, sport and event types. Anime, generic download libraries, replays, highlights and archives are excluded.
- Live includes every browseable Live catalog, without the previous twelve-row limit. Entering a fresh Live section refreshes the account's add-ons and each configured Live manifest before loading catalogs. Unavailable providers retain their saved manifest. Providers with no enabled catalogs show a configuration hint.
- Artwork fits inside its frame without cropping. Live uses landscape cards, square artwork declarations are respected, titles use two lines, and catalog grids wrap according to card widths.
- The original folded-light Luma logo from Fedora is included in the app, launcher icon and TV banner.
- Back restores the same listing views, artwork, loaded pages, focus and scroll positions. Playback ending on a Live channel returns to the retained listing. Browsing another top-level section invalidates the previous section's pages; returning to Live then refreshes it. Settings → Refresh Stremio also invalidates retained pages.
- Playback remains embedded in Luma. The **Mini player** control keeps the same Media3 engine and video surface while browsing. **Return to player** or **↗** restores full playback. **×** stops it. Pause, ten-second seek and mute controls work with the D-pad. A connected keyboard can use **P** to toggle mini/full mode.

The TV mini player stays inside Luma. Pressing Home or opening another app pauses playback; returning resumes it only if it was previously playing. It does not request overlay permissions or depend on Android TV system picture-in-picture support. Closing Luma releases playback. Configuration changes keep the browser and video surface alive.

## Included features

- Glass panels, ambient teal/violet backgrounds, large typography, custom artwork loading and remote focus animations.
- Stremio device linking, installed add-on collection, library, continue watching and queued progress synchronization.
- Home, Movies, Series, Live catalogs, catalog pagination, search, title details, season/episode selection and future-episode guards.
- Direct source discovery, provider request headers, manual source choice and automatic failover.
- Media3 playback with seek, audio/embedded subtitle selection, add-on subtitles, next episode, still-watching prompt and sleep timer.
- Device-bound Android Keystore encryption for account/session, configured add-on URLs, library cache and pending sync changes.

This is an initial implementation, not full Alder feature parity. It plays direct HTTP(S) video streams from your installed add-ons. Raw torrent/infoHash-only playback is not included. Playback codecs depend on the device; the native FFmpeg extension, DRM-license setup, IntroDB/chapter-skip integration, multi-profile PINs, Android TV Watch Next publishing and automatic app updates are not included in this build. Add-on installation is managed in Stremio. No Alder source or bundled assets were copied into the app.

## Build

The project uses Gradle 8.9, Android Gradle Plugin 8.5.2, JDK 17+ and Android SDK 34. Media3 1.3.1 is pinned for compatibility with this installed SDK/toolchain. Set your own `sdk.dir` in `local.properties` or use `ANDROID_HOME`.

```bash
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest :app:lintDebug
```

The release variant currently uses the local development signing key for sideload testing. Preserve that key to update this installation. It is not configured for Play Store publishing.

Install:

```bash
adb connect 192.168.1.8:5555
adb -s 192.168.1.8:5555 install -r artifacts/Luma-TV-0.5.0.apk
```

## Implementation map

| File | Purpose |
|---|---|
| `Models.java` | Add-on capabilities, catalogs, title/video/source records |
| `StremioClient.java` | Linking API, account/library API, parallel add-on requests |
| `SessionStore.java` | AES-GCM / Android Keystore storage |
| `LumaApp.java` | Account state, cached library and serialized sync writes |
| `Glass.java` | TV styling, glass material, icons, visible-only image loading |
| `GlassDialog.java` | Shared modal surface, scrolling choices, confirmations and remote focus |
| `GlassNotice.java` | Transient glass status messages without focus changes |
| `PosterCard.java` | Focus delay, animated backdrop previews, row scrolling and grid bounds |
| `ExitDialog.java` | Branded exit prompt with Stay selected by default |
| `MainActivity.java` | Pairing, browsing, search, library, details and settings |
| `PlayerPanel.java` | Persistent embedded/mini player, controls, tracks, source recovery and progress |
| `PlayerActivity.java` | Compatibility entry point and playback request model |

The background image pipeline gives backdrops their own workers and only schedules visible poster cards. Retained browse pages own their asynchronous catalog callbacks: requests may finish on a detached page without replacing the active screen. Switching sections invalidates those pages, and late results are discarded. Other screens use a screen generation token to drop stale callbacks. Progress writes are serialized and retained locally when the server is unavailable. Subtitle requests use a separate data source so video-provider request headers are not forwarded to subtitle hosts.

## Validation

For 0.5.0, the release/debug builds, all **37 JVM tests**, and Android lint passed.
**15 distinct device checks** passed on the Xiaomi TV: seven shared-dialog/notice
checks, six poster/exit regressions, and two player-menu regressions using local
video in the separate debug package. Source-list screenshots were visually
checked; a pixel comparison verifies that scrolling leaves the heading intact.
The release APK retains the existing certificate and was installed with
`adb install -r`. See `artifacts/VALIDATION-0.5.0.txt` for the runs and limits.

For 0.4.1, the release build and Android release lint passed. On the Xiaomi TV,
the header's equal tab widths and spacing were checked visually and through
accessibility bounds. D-pad traversal, opening Search and Settings, and returning
to browse passed. The APK retains the existing signing certificate and was
installed with `adb install -r`. See `artifacts/VALIDATION-0.4.1.txt`.

For 0.4.0, the Gradle release build, all **37 JVM checks** and Android lint
passed. All **six PosterExitUiTest checks passed on the Xiaomi TV running
Android 14**: expansion/collapse/debounce, unchanged Live dimensions, horizontal
row visibility, grid bounds, default Stay/Back behavior and explicit exit.
These tests use generated views on a debug-only activity and leave account and
library settings alone. The final release APK uses the same certificate as
the installed 0.3.0 app and was installed with `adb install -r`.

Debug builds use the separate `tv.luma.debug` package, keeping fixtures apart
from the owner's release account and preferences. Install both debug APKs, then
run the isolated UI suites on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class tv.luma.GlassDialogUiTest,tv.luma.PosterExitUiTest \
  tv.luma.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Fixture screenshots and validation logs are in `artifacts/`. Signing keys,
personal screenshots and account data are excluded from the repository.

### Earlier 0.3.0 verification

The official Gradle release build, **all 37 JVM checks**, and **Android lint** passed. Version 0.3.0 was installed over 0.1.0 on the TV at `192.168.1.8:44289`; Android reported successful installation and a successful cold launch. The package reports version code 3 / version name 0.3.0, and MainActivity is the resumed activity. No Luma crash was reported during the launch check.

The first fallback APK was rejected because its resource table was compressed. The downloadable APK is now the standard Gradle release build, with its resource table uncompressed and aligned, and the same signing certificate as 0.1.0. The fallback packaging and package checker were corrected too. See `artifacts/VALIDATION-0.3.0.txt`.

The six new `NavigationPlaybackTest` device tests cover retained view/focus/scroll identity, catalog pagination, late responses, mini/full engine and playback state, Live completion, and background pause/resume. Their sources compile, but these regressions have not been run on an unpaired emulator. The TV launch check does not validate every provider stream or mini-player transition.

On a machine/session with ADB access:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=tv.luma.NavigationPlaybackTest
```

`scripts/build-local.py` is a fallback for this workspace. It compiles the current Java sources and all pinned dependency resources using the installed SDK and the dependency model/cache from the previous successful Gradle build, merges the unchanged dependency dex, aligns and signs the APK with the same local key. New machines should use the normal Gradle build instead. `scripts/test-offline.py` runs the account-free JVM fixtures without opening sockets.

The following describes the original 0.1.0 validation, not a device run of this update:


The JVM suite tests capability filtering, URL encoding, search requirements, future episodes, proxy headers, mixed provider failures, included streams, deduplication and metadata fallback. Device instrumentation checks live QR creation/pending state, QR decode, public Cinemeta browse/search/series metadata, Keystore encryption, H.264/AAC fixture playback, pause/seek, and the browse-to-details UI path.

Authenticated account import and cloud watch-state writes need the owner to finish phone pairing; no account credentials were extracted from other TV apps. Successful device tests of the public API do not establish every third-party add-on's availability or stream compatibility.

References used for the protocol and player:

- [Stremio add-on protocol](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md)
- [Stremio linking/API request definitions](https://stremio.github.io/stremio-core/src/stremio_core/types/api/request.rs.html)
- [Stream objects and request headers](https://stremio.github.io/stremio-addon-sdk/api/responses/stream.html)
- [Android Media3 player lifecycle](https://developer.android.com/media/media3/exoplayer/hello-world)

# Private viewing reports (0.6.0)

This version records viewing in Luma on the TV and lets the paired owner's
Fedora computer retrieve it. Collection stays off until owner pairing. The
TV does not provide a readable watch-history screen or send these reports to
Stremio, an analytics vendor, email, or a public service.

The app's existing Stremio library/progress synchronization remains separate
and still communicates with Stremio when an account is connected.

## What a report contains

- Movie/series title or live channel name; season/episode numbers when known.
- A random session identifier and start/update timestamps in UTC milliseconds.
- Active viewing milliseconds, plus session-finished/media-ended indicators.

No playback URLs, provider names, headers, tokens, account identifiers, artwork,
subtitles, device identifiers or content IDs are copied into the report.
Live reports identify the channel; this app has no programme-guide integration.

Viewing is **elapsed time while Media3 reports actual playback**, including the
in-app mini player. It excludes paused, buffering, suppressed and background
playback. Seeking does not add skipped media time, and speed changes do not
multiply elapsed viewing. It measures playback rather than human attention.
Clock gaps over five seconds are discarded because playback during process
suspension cannot be established. Checkpoints are written every 15 seconds
and on stop/background/end. A process crash can lose the last checkpoint
interval; an unfinished snapshot is retained rather than invented as completed.

## Set up on Fedora

The computer needs Python 3, `secret-tool`, an unlocked Secret Service keyring,
and an authorized ADB connection to the TV. Install a compatible APK first.

```sh
python3 -m venv ~/.local/share/luma-reports/runtime
~/.local/share/luma-reports/runtime/bin/pip install -r scripts/requirements-reports.txt
~/.local/share/luma-reports/runtime/bin/python scripts/private-reports.py init
~/.local/share/luma-reports/runtime/bin/python scripts/private-reports.py pair --device 192.168.1.8:5555
```

Compare the complete fingerprint printed on the computer with the TV dialog,
then select **Pair this owner**. The default selection is Cancel. Pairing a
different key requires revoking the existing owner first. For the separate
test APK, add `--package tv.luma.debug` to pair/collect/watch commands.

```sh
# Fetch now; Luma must be running on the TV.
~/.local/share/luma-reports/runtime/bin/python scripts/private-reports.py collect

# Retrieve automatically every minute while the TV is available.
~/.local/share/luma-reports/runtime/bin/python scripts/private-reports.py watch

# Read collected reports. Nothing is decrypted to disk by these commands.
~/.local/share/luma-reports/runtime/bin/python scripts/private-reports.py show
~/.local/share/luma-reports/runtime/bin/python scripts/private-reports.py show --json
```

Use `--adb /absolute/path/to/adb` when needed. This computer's toolchain also
has ADB at `~/Android/Sdk/platform-tools/adb`. The watch process retries offline
TVs without starting Luma or interrupting another app. Stop it with Ctrl+C.
No startup service is installed or enabled by these commands.

Reports and the password-encrypted owner private key live under
`~/.local/share/luma-reports/` (or `$XDG_DATA_HOME/luma-reports/`). The randomly
generated private-key password lives in the desktop keyring under application
`tv.luma.reports`, purpose `private-key`. Directories are private (0700) and
files are 0600. Back up both the encrypted private key and the original desktop
keyring securely; losing either prevents reading existing reports.

## Security boundary

The TV has only the owner's RSA public key. Each checkpoint uses a fresh
AES-256-GCM key/nonce, with the AES key wrapped using RSA-OAEP SHA-256/MGF1
SHA-256 (3072 bits minimum). The versioned envelope/header is authenticated.
All report metadata is inside the encrypted envelope. The TV stores it in
the app's private, backup-excluded directory using atomic replacement.

The report endpoint listens on **127.0.0.1 only**, on TV port 37821 (37822 for
the debug package). The collector uses a temporary, loopback-only ADB forward
and removes it afterward. Every report request must sign a fresh, single-use
30-second challenge with the owner's private key. Captured signatures cannot
be replayed. Challenges are bounded, request sizes/timeouts are bounded,
browser Origin requests are rejected, and responses disable caching. There
is no delete/configuration HTTP API or public LAN reporting listener.

The shell-only pairing activity is protected by Android's `DUMP` permission.
An ordinary installed app cannot nominate a report owner. The TV also shows
the owner fingerprint for comparison, because an authorized debugging host
has broad control of the device. Use trusted networks; encrypted Android
wireless debugging is preferable to legacy TCP ADB for ongoing use. Disabling
debugging stops collection from the computer without stopping local tracking.

The scheme protects report contents from other ordinary TV apps, other users
on the network, and copied encrypted files. A compromised/rooted TV can falsify
playback observations. Administrator access or malware running as the owner
on the computer can access its keyring. Encryption does not establish human
attention or protect a compromised endpoint.

## Pause, retention, and deletion

Open **Settings → Private reports** to pause/resume collection or **Revoke
owner and delete TV reports**. Revocation deletes the TV key and ciphertext;
newly paired owners do not receive queued data from the previous owner.
Disabling and re-enabling starts a fresh report segment. Ordinary reports are
retained for at most 90 days and up to 2,000 sessions on both devices; pruning
runs on writes/collection. Viewer output also filters expired records. Report
files already copied elsewhere are not remotely erased. To delete collected
reports, stop the collector and remove this computer's `records/` directory.

Stremio disconnect and report-owner revocation are separate controls. Account
disconnect does not silently revoke the TV owner's reporting configuration.

## Signing and deployment

The installed TV app is `tv.luma` 0.5.0, version code 6. Its signer SHA-256 is:

```
98de42adaee0e1c6178bffaecd6dc18e301fa8cd6de14bed087c8ac78db032f2
```

The checked GitHub main branch, its four commits and the v0.5.0 release contain
source/APKs but no signing keystore. `signingConfigs.debug` in the original
build refers to the builder's local `~/.android/debug.keystore`; that private
key cannot be recovered from an APK or its certificate.

0.6.0 release builds use an explicit original keystore, supplied through
`LUMA_SIGNING_KEYSTORE`, `LUMA_SIGNING_STORE_PASSWORD`,
`LUMA_SIGNING_KEY_ALIAS`, and `LUMA_SIGNING_KEY_PASSWORD`. Without those, Gradle
produces an unsigned release APK. Debug builds use the separate `tv.luma.debug`
package and the **Luma TV Preview** label. Never uninstall the owner's release
to work around a signature mismatch: that would erase device-bound account
data. Compare the signed update's certificate with the fingerprint above
before running `adb install -r`.

## Validation commands

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
JAVA_HOME=/path/to/jdk17 /path/to/reports-venv/bin/python scripts/test_private_reports.py -v
adb shell am instrument -w -r -e class tv.luma.PrivateReportsDeviceTest,tv.luma.NavigationPlaybackTest tv.luma.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Fixtures require an account-free debug app and use generated local video.
The debug-only, shell-protected `ReportFixtureActivity` supports a full
owner-computer collection check without account credentials or provider streams.
References: [Media3 playback state](https://developer.android.com/media/media3/exoplayer/listening-to-player-events),
[Android app-specific storage](https://developer.android.com/training/data-storage/app-specific),
[ADB](https://developer.android.com/tools/adb).

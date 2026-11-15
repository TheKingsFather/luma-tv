# Private reports validation — 2026-11-15

Target: Xiaomi MiTV-MZTU0, Android 14. Installed owner release:
`tv.luma` 0.5.0 / version code 6. All new playback fixtures ran in the separate,
account-free `tv.luma.debug` package; the owner's release was not replaced.

- Gradle JVM suite: **45 passed**, including eight new clock, crypto, storage
  retention and signed-request/replay tests.
- Owner Python suite: **4 passed**, covering Java/Python crypto compatibility,
  wrong keys, tampering, malformed/private fields and private atomic files.
- Android lint: **0 errors**, 45 warnings (mostly existing UI/deprecation
  warnings; one new intentional synchronous preference-commit warning).
- Debug, Android-test and **unsigned** release APK builds succeeded.
- New TV instrumentation: **3 passed**. Actual local H.264/AAC playback
  excludes pause, seek and background time; mini/full playback keeps counting.
  Device encryption/storage, disable and revoke/delete checks passed.
- Existing TV navigation/playback suite: **7 of 8 passed**. The test
  `backRestoresIdenticalCardsFocusAndBothScrollAxesWithoutRequests` fails on
  scroll restoration (750 expected, 831 observed). The same test was run on
  the unchanged source snapshot in `tv.luma.baseline`; it also fails, at its
  preceding focus-restoration assertion. This does not establish an identical
  root cause; the known baseline failure remains outside this reporting change.
- Full owner flow: generated an owner key encrypted with a random password held
  in the desktop Secret Service keyring, paired its public key on the TV,
  fetched an empty archive, played the debug-only generated fixture, and
  collected/decrypted its single report on Fedora. The report contained the
  expected test title and **21,677 ms** active viewing. Only ciphertext was
  written to the computer's report archive.

No user account keys, provider streams, production library writes or real
viewing history were used as fixtures. No public reporting service or automatic
collector startup service was enabled.

The original release signer is documented in `docs/private-reports.md`. No
matching private keystore was found in the repository's current files, four
commits or release assets. Its original keystore is required for an in-place
release update; a newly generated debug key is not a substitute. The installed
release's version and last-update timestamp were verified unchanged afterward.

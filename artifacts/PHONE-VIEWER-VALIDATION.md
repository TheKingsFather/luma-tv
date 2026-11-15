# iPhone viewer validation — 2026-11-15

- Eight Flask application tests pass: owner identity, unauthorized/other-user
  rejection, exact host/origin, no-cache/CSP headers, read-only methods, no
  archive/key file routes, safe failure responses and static-shell isolation.
- The production archive loader successfully decrypted the existing generated
  TV-fixture record through an authorized local test request. A request without
  the owner identity was rejected. This used simulated proxy headers; it is
  not an iPhone/Tailscale end-to-end test.
- The JavaScript passes `node --check`. Python modules compile in the report
  virtual environment with Flask 3.1.3 and Waitress 3.0.2.
- The loopback demo serves three clearly marked generated examples and does
  not load the report archive or keyring.
- Browser-based QA could not run: the computer-use browser host returned
  unavailable. Actual Safari layout and interaction remain to be checked.
- Official Tailscale 1.104.7 static binaries were downloaded from the vendor
  and verified against its SHA-256 checksum. A separate, unprivileged userspace
  daemon starts successfully through the user's systemd instance, with its
  state in a 0700 directory and vendor log uploads disabled.
- Account activation is pending the owner's sign-in. The production viewer
  service has not been enabled and no private Safari URL has been verified.
  After sign-in, `luma-reports-phone connect` pins the actual owner login and
  HTTPS hostname, starts the viewer, and configures private Tailscale Serve.
- No public hosting or Funnel is configured. No report decryption key, network
  state, user login, sign-in URL or report data is committed to the repository.

The separate original Android signing-key blocker still applies. A TV-update
notice is shown by the phone viewer until a compatible TV update is installed.

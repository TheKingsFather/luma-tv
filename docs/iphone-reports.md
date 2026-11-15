# Open your private reports on iPhone

The iPhone viewer runs on your Fedora computer. It reads the existing encrypted
report archive and decrypts in memory using the original desktop keyring. Your
private key stays on Fedora. Safari receives only the reports it displays,
over your private HTTPS connection.

## First-time setup

1. Install [Tailscale for iPhone](https://tailscale.com/download/ios), sign in,
   and allow its VPN configuration. Use the same Tailscale account on Fedora.
2. Complete the computer sign-in link printed by `luma-reports-phone login`.
   The computer is named **luma-reports**.
3. Run `luma-reports-phone connect` on Fedora. It pins the signed-in owner's
   login and the computer's private HTTPS hostname. If Tailscale asks to enable
   HTTPS, follow its link, then run `connect` again.
4. Open the printed `https://luma-reports.<your-tailnet>.ts.net` address in
   Safari with Tailscale connected. No extra report password is required:
   access is restricted to your Tailscale login.
5. Optionally use Safari's **Share → Add to Home Screen** for a Luma shortcut.

Your Fedora computer must be switched on, with your user session logged in
and keyring unlocked. The viewer starts with your user session after setup.
Signing out of Fedora or switching off the computer makes it unavailable.

The existing TV app still needs the signed 0.6.0 update before it produces
real viewing reports. Until then, the viewer displays a clear TV-update notice.
The generated test clip in the archive verifies collection and decryption;
it is not real viewing history.

## Using the viewer

- Choose Today, 7 days, 30 days or 90 days.
- Filter Movies, Series or Live, or search a title/channel.
- Each session shows its title, episode when available, start time and active
  playback duration. Dates use your iPhone's local timezone.
- The refresh control reloads reports already collected on Fedora. The page
  also refreshes while visible every minute. TV collection is performed by
  the original `private-reports.py collect/watch` tool, separately from viewing.

Report data is never saved in browser local storage, IndexedDB or an offline
cache. History is removed from the page when it becomes hidden. Safari and
the operating system may still hold rendered content in memory or screenshots;
secure the iPhone itself with its normal device lock.

## How access is restricted

Tailscale Serve provides private HTTPS access and authenticated identity headers.
The backend listens only on **127.0.0.1:8845**. It requires the exact configured
HTTPS hostname and the owner's `Tailscale-User-Login` on every report request.
Other tailnet users, missing identity, tagged devices and Funnel/public traffic
receive no reports. Merely joining the same tailnet is insufficient.

This trusts Tailscale Serve as the identity proxy. Serve strips caller-supplied
identity headers and replaces them with the verified connecting identity. A
process running locally as the desktop owner could spoof those headers, but
that OS account already has access to the report keyring. The backend must
never be rebound to `0.0.0.0` or exposed through another unauthenticated proxy.

The viewer is read-only, uses no report login cookies or URL secrets, rejects
cross-origin API calls, disables caching, includes a restrictive Content Security
Policy, and loads no third-party fonts, scripts or analytics. Text is rendered
with `textContent`, so titles cannot become HTML. Errors contain no report
contents or file paths. Only the owner's devices should use the owner's login;
an unlocked device signed into that account can read the reports.

The private connection uses a separate user-level Tailscale daemon in userspace
mode. It does not change system DNS, advertise routes, enable Tailscale SSH,
or open a public reporting site. Vendor log uploads are disabled for this daemon.
Its private state is stored under `~/.local/share/luma-reports/tailscale/` with
owner-only access. HTTPS certificates contain the generic computer name and
tailnet domain in the public certificate-transparency ledger, not watch history.

## Setup on another Fedora computer

Install the report dependencies from `scripts/requirements-reports.txt` in a
virtual environment. Download and verify the official Linux static Tailscale
binaries, then run:

```sh
/path/to/reports-venv/bin/python scripts/setup-phone-viewer.py install --tailscale-bin /path/to/tailscale-binaries
/path/to/reports-venv/bin/python scripts/setup-phone-viewer.py login --tailscale-bin /path/to/tailscale-binaries
/path/to/reports-venv/bin/python scripts/setup-phone-viewer.py connect --tailscale-bin /path/to/tailscale-binaries
```

The original encrypted owner private key and desktop keyring must already
be available on that computer. Setup does not generate or send a new key.

On this computer, the short command is `luma-reports-phone`. Check the private
connection with `luma-reports-phone status`. Stop it and the viewer with
`luma-reports-phone stop`. Removing a lost iPhone from Tailscale revokes that
device's network access. To change the allowed owner, reconfigure from the
newly signed-in owner's local desktop session.

## Validation

```sh
/path/to/reports-venv/bin/python scripts/test_phone_viewer.py -v
node --check scripts/phone-viewer/app.js
```

For a local preview that contains generated examples only and never reads the
keyring or real archive:

```sh
/path/to/reports-venv/bin/python scripts/phone-viewer.py --demo --port 8846
```

References: [Tailscale iOS setup](https://tailscale.com/docs/install/ios),
[Serve and its identity headers](https://tailscale.com/docs/features/tailscale-serve),
[userspace networking](https://tailscale.com/docs/concepts/userspace-networking),
[HTTPS certificates](https://tailscale.com/docs/how-to/set-up-https-certificates).

#!/usr/bin/env python3
"""Install the private viewer's user services; connect through owner Tailscale login."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(os.environ.get('XDG_DATA_HOME', Path.home() / '.local/share')) / 'luma-reports'
SOURCE = Path(__file__).resolve().parent
UNITS = Path.home() / '.config/systemd/user'
STATE = ROOT / 'tailscale'
SOCKET = STATE / 'tailscaled.sock'


def run(args, **kwargs):
    return subprocess.run(list(map(str, args)), check=True, **kwargs)


def private_write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.parent.chmod(0o700)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as out:
        out.write(text)
    path.chmod(0o600)


def systemd_quote(value):
    return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%') + '"'


def service(args, description, after=''):
    return f'''[Unit]
Description={description}
{after}
[Service]
Type=simple
ExecStart={' '.join(systemd_quote(a) for a in args)}
Restart=on-failure
RestartSec=10
UMask=0077
NoNewPrivileges=yes
PrivateTmp=yes

[Install]
WantedBy=default.target
'''


def install(args):
    if not (args.tailscale_bin / 'tailscale').is_file() or not (args.tailscale_bin / 'tailscaled').is_file():
        raise RuntimeError('Install the official static Tailscale binaries first.')
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    STATE.chmod(0o700)
    private_write(UNITS / 'luma-private-network.service', service([
        args.tailscale_bin / 'tailscaled', '--tun=userspace-networking',
        '--state=' + str(STATE / 'tailscaled.state'), '--socket=' + str(SOCKET),
        '--no-logs-no-support'], 'Luma private phone network'))
    private_write(UNITS / 'luma-phone-viewer.service', service([
        sys.executable, SOURCE / 'phone-viewer.py'], 'Luma private viewing reports',
        'After=luma-private-network.service'))
    run(['systemctl', '--user', 'daemon-reload'])
    run(['systemctl', '--user', 'enable', '--now', 'luma-private-network.service'])


def tailscale(args, *command, capture=True):
    return run([args.tailscale_bin / 'tailscale', '--socket=' + str(SOCKET), *command],
               capture_output=capture, text=True, timeout=20)


def connect(args):
    status = json.loads(tailscale(args, 'status', '--json').stdout)
    if status.get('BackendState') != 'Running':
        raise RuntimeError('Finish Tailscale sign-in, then run connect again.')
    node = status['Self']
    if node.get('Tags'):
        raise RuntimeError('Use an owner login rather than a tagged service identity.')
    owner = status['User'][str(node['UserID'])]['LoginName']
    hostname = node['DNSName'].rstrip('.')
    if not hostname.endswith('.ts.net'):
        raise RuntimeError('Enable MagicDNS for your private network.')
    config = {'origin': 'https://' + hostname, 'owner_login': owner, 'tv_update_pending': True}
    private_write(ROOT / 'phone-viewer.json', json.dumps(config, indent=2) + '\n')
    run(['systemctl', '--user', 'enable', '--now', 'luma-phone-viewer.service'])
    # Serve is tailnet-only. This helper never enables Funnel/public access.
    result = tailscale(args, 'serve', '--bg', '--https=443', 'http://127.0.0.1:8845', capture=False)
    print('\nOpen on your iPhone with Tailscale connected: ' + config['origin'])
    print('Only the signed-in owner account can read reports. Keep this computer on and logged in.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['install', 'login', 'connect', 'status', 'stop'])
    parser.add_argument('--tailscale-bin', type=Path, default=Path.home() / '.local/share/luma-build/tailscale')
    args = parser.parse_args()
    if args.command == 'install':
        install(args)
    elif args.command == 'login':
        # CLI prints the sign-in URL. No account credential is read by this tool.
        subprocess.run([str(args.tailscale_bin / 'tailscale'), '--socket=' + str(SOCKET),
                        'up', '--hostname=luma-reports', '--accept-dns=false',
                        '--accept-routes=false', '--timeout=10s'], check=False)
    elif args.command == 'connect':
        connect(args)
    elif args.command == 'status':
        result = tailscale(args, 'status', '--json')
        status = json.loads(result.stdout)
        print('Private connection: ' + status.get('BackendState', 'Unknown'))
        config = ROOT / 'phone-viewer.json'
        if config.exists():
            print('iPhone address: ' + json.loads(config.read_text())['origin'])
    elif args.command == 'stop':
        subprocess.run(['systemctl', '--user', 'disable', '--now', 'luma-phone-viewer.service'], check=False)
        try:
            tailscale(args, 'serve', '--https=443', 'off')
        finally:
            subprocess.run(['systemctl', '--user', 'disable', '--now', 'luma-private-network.service'], check=False)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
        print('Phone viewer setup needs attention: ' + str(error), file=sys.stderr)
        raise SystemExit(1)

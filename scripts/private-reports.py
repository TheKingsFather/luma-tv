#!/usr/bin/env python3
"""Owner-only Luma reporting. Private key password lives in the desktop keyring."""
import argparse
import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import struct
import subprocess
import sys
import time
import uuid

import requests
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(os.environ.get('XDG_DATA_HOME', Path.home() / '.local/share')) / 'luma-reports'
CONTEXT = b'luma-reports-v1:'


def private_write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.parent.chmod(0o700)
    temporary = path.with_name(path.name + '.tmp-' + secrets.token_hex(6))
    try:
        with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as out:
            out.write(value)
            out.flush()
            os.fsync(out.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def key_password(create=False):
    command = ['secret-tool', 'lookup', 'application', 'tv.luma.reports', 'purpose', 'private-key']
    result = subprocess.run(command, capture_output=True, timeout=20)
    password = result.stdout.strip() if result.returncode == 0 else b''
    if not password and create:
        password = base64.urlsafe_b64encode(secrets.token_bytes(32))
        result = subprocess.run(['secret-tool', 'store', '--label=Luma private viewing reports',
                                 'application', 'tv.luma.reports', 'purpose', 'private-key'],
                                input=password, capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError('Unlock the desktop keyring before setting up reports.')
    if not password:
        raise RuntimeError('Unlock the original desktop keyring to read reports.')
    return password


def init_owner():
    path = ROOT / 'owner-key.pem'
    if path.exists():
        return load_key()
    key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    password = key_password(create=True)
    private_write(path, key.private_bytes(serialization.Encoding.PEM,
                                         serialization.PrivateFormat.PKCS8,
                                         serialization.BestAvailableEncryption(password)))
    return key


def load_key():
    return serialization.load_pem_private_key((ROOT / 'owner-key.pem').read_bytes(), key_password())


def public_bytes(key):
    return key.public_key().public_bytes(serialization.Encoding.DER,
                                        serialization.PublicFormat.SubjectPublicKeyInfo)


def decrypt_record(key, record):
    if not 426 <= len(record) <= 18000 or record[:8] != b'LUMARPT1':
        raise ValueError('Invalid report envelope')
    wrapped_len = struct.unpack('>H', record[8:10])[0]
    if wrapped_len not in (384, 512) or len(record) < 10 + wrapped_len + 32:
        raise ValueError('Invalid report key')
    wrapped = record[10:10 + wrapped_len]
    header_end = 10 + wrapped_len + 12
    nonce = record[header_end - 12:header_end]
    length = struct.unpack('>I', record[header_end:header_end + 4])[0]
    if length != len(record) - header_end - 4:
        raise ValueError('Invalid report length')
    aes = key.decrypt(wrapped, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),
                                           algorithm=hashes.SHA256(), label=None))
    plain = AESGCM(aes).decrypt(nonce, record[header_end + 4:], record[:header_end])
    row = json.loads(plain)
    if not isinstance(row, dict):
        raise ValueError('Invalid report record')
    if row.get('version') != 1 or str(uuid.UUID(row['session'])) != row['session']:
        raise ValueError('Invalid report identity')
    if type(row.get('watched_ms')) is not int or not 0 < row['watched_ms'] <= 90 * 86400000:
        raise ValueError('Invalid viewing duration')
    for field in ('started_at_ms', 'updated_at_ms'):
        if type(row.get(field)) is not int or not 0 < row[field] <= 253402300799000:
            raise ValueError('Invalid report timestamp')
    for field in ('season', 'episode'):
        if field in row and (type(row[field]) is not int or not 0 <= row[field] <= 100000):
            raise ValueError('Invalid episode number')
    allowed = {'version', 'session', 'title', 'type', 'started_at_ms', 'updated_at_ms',
               'watched_ms', 'finished', 'ended', 'season', 'episode'}
    if row.keys() - allowed or not isinstance(row.get('title'), str) or len(row['title']) > 256:
        raise ValueError('Unexpected report fields')
    return row


def adb(args, *command):
    result = subprocess.run([args.adb, '-s', args.device, *command], capture_output=True,
                            text=True, timeout=20)
    if result.returncode:
        raise RuntimeError('The authorized TV connection is unavailable.')
    return result.stdout.strip()


def connect(args):
    result = subprocess.run([args.adb, 'connect', args.device], capture_output=True,
                            text=True, timeout=12)
    if result.returncode or adb(args, 'get-state') != 'device':
        raise RuntimeError('Connect and authorize the TV before collecting reports.')


def get_bounded(session, url, limit, headers=None):
    with session.get(url, headers=headers, timeout=(3, 15), stream=True,
                     allow_redirects=False) as response:
        if response.status_code != 200:
            raise RuntimeError('Private reports are not paired, running, or authorized.')
        content = bytearray()
        for part in response.iter_content(65536):
            content.extend(part)
            if len(content) > limit:
                raise ValueError('Report response too large')
        return json.loads(content)


def collect(args, key):
    connect(args)
    device_port = '37822' if args.package.endswith('.debug') else '37821'
    port = adb(args, 'forward', 'tcp:0', 'tcp:' + device_port)
    if not port.isdigit() or not 0 < int(port) < 65536:
        raise RuntimeError('Could not open a private TV tunnel.')
    try:
        with requests.Session() as session:
            session.trust_env = False  # Never send local report traffic through a proxy.
            endpoint = f'http://127.0.0.1:{port}'
            nonce = get_bounded(session, endpoint + '/challenge', 1024)['challenge']
            if not isinstance(nonce, str) or len(nonce) != 43:
                raise ValueError('Invalid report challenge')
            signature = key.sign(CONTEXT + nonce.encode('ascii'), padding.PKCS1v15(), hashes.SHA256())
            payload = get_bounded(session, endpoint + '/reports', 40 * 1024 * 1024,
                                  {'X-Luma-Challenge': nonce,
                                   'Authorization': 'Luma ' + base64.b64encode(signature).decode()})
        if payload.get('version') != 1 or not isinstance(payload.get('records'), list) or len(payload['records']) > 2000:
            raise ValueError('Invalid report archive')
        records = ROOT / 'records'
        count = 0
        for value in payload['records']:
            encrypted = base64.b64decode(value, validate=True)
            row = decrypt_record(key, encrypted)
            path = records / (row['session'] + '.rpt')
            if path.exists():
                old = decrypt_record(key, path.read_bytes())
                if old['watched_ms'] > row['watched_ms'] or old['updated_at_ms'] >= row['updated_at_ms']:
                    continue
            private_write(path, encrypted)
            count += 1
        # Match the TV's bounded local retention; plaintext never goes to disk.
        existing = sorted(records.glob('*.rpt'), key=lambda p: p.stat().st_mtime, reverse=True)
        for index, path in enumerate(existing):
            row = decrypt_record(key, path.read_bytes())
            if index >= 2000 or row['updated_at_ms'] < (time.time() - 90 * 86400) * 1000:
                path.unlink()
        return count
    finally:
        adb(args, 'forward', '--remove', 'tcp:' + port)


def show(key, as_json):
    rows = [decrypt_record(key, p.read_bytes()) for p in (ROOT / 'records').glob('*.rpt')]
    cutoff = (time.time() - 90 * 86400) * 1000
    rows = sorted((r for r in rows if r['updated_at_ms'] >= cutoff), key=lambda r: r['started_at_ms'], reverse=True)
    if as_json:
        print(json.dumps(rows, indent=2, ensure_ascii=True))
        return
    if not rows:
        print('No viewing reports collected yet.')
    for row in rows:
        date = dt.datetime.fromtimestamp(row['started_at_ms'] / 1000).strftime('%Y-%m-%d %H:%M')
        seconds = row['watched_ms'] // 1000
        duration = f'{seconds // 3600:d}:{seconds // 60 % 60:02d}:{seconds % 60:02d}'
        title = ''.join(c if c.isprintable() else ' ' for c in row['title'])
        episode = f" · S{row['season']} E{row['episode']}" if 'season' in row else ''
        print(f'{date}   {duration}   {title}{episode}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['init', 'pair', 'collect', 'watch', 'show'])
    parser.add_argument('--adb', default=shutil.which('adb') or str(Path.home() / 'Android/Sdk/platform-tools/adb'))
    parser.add_argument('--device', default='192.168.1.8:5555')
    parser.add_argument('--package', default='tv.luma', choices=['tv.luma', 'tv.luma.debug'])
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args()
    key = init_owner() if args.command in ('init', 'pair') else load_key()
    if args.command in ('init', 'pair'):
        print('Owner fingerprint: ' + hashlib.sha256(public_bytes(key)).hexdigest())
    if args.command == 'pair':
        connect(args)
        adb(args, 'shell', 'am', 'start', '-n', args.package + '/tv.luma.ReportSetupActivity',
            '--es', 'owner_public_key', base64.b64encode(public_bytes(key)).decode())
        print('Compare the fingerprint on your TV, then select Pair this owner.')
    elif args.command == 'collect':
        print(f'Collected {collect(args, key)} updated encrypted reports.')
    elif args.command == 'show':
        show(key, args.json)
    elif args.command == 'watch':
        while True:
            try:
                collect(args, key)
            except (OSError, ValueError, RuntimeError, requests.RequestException, subprocess.SubprocessError):
                pass  # TV offline or Luma not open; retry without logging viewing data.
            time.sleep(60)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        pass
    except Exception:
        print('Private reports unavailable. Check the TV, owner pairing and unlocked desktop keyring.', file=sys.stderr)
        raise SystemExit(1)

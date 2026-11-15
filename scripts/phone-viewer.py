#!/usr/bin/env python3
"""Private Safari viewer behind Tailscale Serve. Never bind this app to the LAN."""
import argparse
import importlib.util
import json
import logging
from pathlib import Path
import time
from urllib.parse import urlsplit

from flask import Flask, jsonify, request, send_file
from waitress import serve

spec = importlib.util.spec_from_file_location('luma_private_reports', Path(__file__).with_name('private-reports.py'))
reports = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reports)
ASSETS = Path(__file__).with_name('phone-viewer')


def read_reports(key):
    rows = []
    latest = 0
    paths = list((reports.ROOT / 'records').glob('*.rpt'))
    if len(paths) > 2000:
        raise ValueError('Archive exceeds retention limit')
    cutoff = (time.time() - 90 * 86400) * 1000
    for path in paths:
        if path.stat().st_size > 18000:
            raise ValueError('Invalid encrypted report')
        row = reports.decrypt_record(key, path.read_bytes())
        if row['updated_at_ms'] >= cutoff:
            rows.append(row)
            latest = max(latest, int(path.stat().st_mtime * 1000))
    return {'records': sorted(rows, key=lambda r: r['started_at_ms'], reverse=True),
            'collected_at_ms': latest or None, 'demo': False}


def demo_reports():
    now = int(time.time() * 1000)
    return {'demo': True, 'collected_at_ms': now, 'records': [
        {'session': 'sample-one', 'title': 'The Last Lighthouse', 'type': 'movie',
         'started_at_ms': now - 3600000, 'updated_at_ms': now, 'watched_ms': 4262000, 'finished': True},
        {'session': 'sample-two', 'title': 'A World Apart', 'type': 'series', 'season': 2, 'episode': 4,
         'started_at_ms': now - 86400000, 'updated_at_ms': now - 84000000, 'watched_ms': 2415000, 'finished': True},
        {'session': 'sample-three', 'title': 'Nature Live', 'type': 'tv',
         'started_at_ms': now - 90000000, 'updated_at_ms': now - 89000000, 'watched_ms': 817000, 'finished': False},
    ]}


def create_app(origin, owner_login, loader, *, demo=False, tv_update_pending=False):
    parsed = urlsplit(origin)
    if not demo and (parsed.scheme != 'https' or not parsed.hostname or
                     not parsed.hostname.endswith('.ts.net') or parsed.port or
                     parsed.path or parsed.query or parsed.fragment or parsed.username):
        raise ValueError('Use the exact private HTTPS Tailscale Serve origin')
    if not owner_login or len(owner_login) > 256 or any(ord(c) < 32 for c in owner_login):
        raise ValueError('An explicit owner login is required')
    app = Flask(__name__, static_folder=None)
    app.config['MAX_CONTENT_LENGTH'] = 8192
    app.logger.addHandler(logging.NullHandler())
    app.logger.propagate = False

    @app.before_request
    def boundary():
        if request.method not in ('GET', 'HEAD'):
            return jsonify(error='Read-only viewer'), 405
        if request.host.lower() != parsed.netloc.lower():
            return jsonify(error='Unrecognized viewer address'), 403
        if request.query_string:
            return jsonify(error='Unsupported request'), 400
        if request.headers.get('Origin') not in (None, origin):
            return jsonify(error='Unrecognized origin'), 403
        if request.path.startswith('/api/') and not demo:
            # Serve strips caller-supplied identity headers and supplies its own.
            # Loopback binding is essential: same-OS-user processes are trusted.
            login = request.headers.get('Tailscale-User-Login', '')
            if login.casefold() != owner_login.casefold():
                return jsonify(error='Connect using the owner’s Tailscale account.'), 403

    @app.after_request
    def privacy(response):
        response.headers.update({
            'Cache-Control': 'private, no-store, max-age=0', 'Pragma': 'no-cache',
            'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff',
            'X-Frame-Options': 'DENY',
            'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
            'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
        })
        if not demo:
            response.headers['Strict-Transport-Security'] = 'max-age=31536000'
        return response

    @app.get('/')
    def index():
        return send_file(ASSETS / 'index.html', conditional=False)

    @app.get('/app.js')
    def javascript():
        return send_file(ASSETS / 'app.js', conditional=False)

    @app.get('/style.css')
    def stylesheet():
        return send_file(ASSETS / 'style.css', conditional=False)

    @app.get('/mark.svg')
    def mark():
        return send_file(ASSETS / 'mark.svg', conditional=False)

    @app.get('/api/reports')
    def data():
        try:
            payload = loader()
            payload['tv_update_pending'] = bool(tv_update_pending)
            return jsonify(payload)
        except Exception:
            return jsonify(error='Reports are unavailable. Check the computer and unlock its keyring.'), 503

    @app.errorhandler(404)
    def missing(_error):
        return jsonify(error='Not found'), 404

    return app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=reports.ROOT / 'phone-viewer.json')
    parser.add_argument('--port', type=int, default=8845)
    parser.add_argument('--demo', action='store_true', help='Generated examples only; no keyring or archive access')
    args = parser.parse_args()
    if args.demo:
        app = create_app(f'http://127.0.0.1:{args.port}', 'demo', demo_reports, demo=True)
    else:
        config = json.loads(args.config.read_text())
        key = reports.load_key()
        app = create_app(config['origin'], config['owner_login'], lambda: read_reports(key),
                         tv_update_pending=config.get('tv_update_pending', False))
    # Incoming public/tailnet traffic must pass through the authenticated proxy.
    serve(app, host='127.0.0.1', port=args.port, threads=2, connection_limit=32,
          channel_timeout=15, max_request_header_size=8192, max_request_body_size=8192,
          clear_untrusted_proxy_headers=True)


if __name__ == '__main__':
    main()

import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('phone_viewer', Path(__file__).with_name('phone-viewer.py'))
viewer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(viewer)


class PhoneViewerTests(unittest.TestCase):
    def setUp(self):
        self.calls = 0
        def loader():
            self.calls += 1
            return {'records': [{'title': '<script>private title</script>', 'watched_ms': 21000}],
                    'collected_at_ms': 1, 'demo': False}
        self.app = viewer.create_app('https://luma-reports.example.ts.net', 'owner@example.com', loader)
        self.client = self.app.test_client()

    def get(self, path='/api/reports', headers=None, **kwargs):
        return self.client.get(path, base_url='https://luma-reports.example.ts.net', headers=headers or {}, **kwargs)

    def test_owner_receives_reports(self):
        response = self.get(headers={'Tailscale-User-Login': 'owner@example.com'})
        self.assertEqual(200, response.status_code)
        self.assertEqual(21000, response.json['records'][0]['watched_ms'])
        self.assertEqual(1, self.calls)

    def test_no_identity_other_tailnet_members_and_forwarded_identity_are_rejected(self):
        for headers in ({}, {'Tailscale-User-Login': 'other@example.com'},
                        {'X-Forwarded-User': 'owner@example.com'}):
            response = self.get(headers=headers)
            self.assertEqual(403, response.status_code)
            self.assertNotIn('private title', response.text)
        self.assertEqual(0, self.calls)

    def test_host_and_origin_boundaries_are_enforced(self):
        headers = {'Tailscale-User-Login': 'owner@example.com'}
        response = self.client.get('/api/reports', base_url='https://evil.invalid', headers=headers)
        self.assertEqual(403, response.status_code)
        response = self.get(headers={**headers, 'Origin': 'https://evil.invalid'})
        self.assertEqual(403, response.status_code)
        self.assertEqual(0, self.calls)

    def test_cache_csp_and_no_frame_headers(self):
        response = self.get(headers={'Tailscale-User-Login': 'owner@example.com'})
        self.assertIn('no-store', response.headers['Cache-Control'])
        self.assertIn("frame-ancestors 'none'", response.headers['Content-Security-Policy'])
        self.assertIn("script-src 'self'", response.headers['Content-Security-Policy'])
        self.assertEqual('no-referrer', response.headers['Referrer-Policy'])
        self.assertNotIn('Access-Control-Allow-Origin', response.headers)

    def test_read_only_no_file_serving_and_no_query_parameters(self):
        headers = {'Tailscale-User-Login': 'owner@example.com'}
        response = self.client.post('/api/reports', base_url='https://luma-reports.example.ts.net', headers=headers)
        self.assertEqual(405, response.status_code)
        for path in ('/owner-key.pem', '/records', '/../../owner-key.pem'):
            self.assertEqual(404, self.get(path).status_code)
        self.assertEqual(400, self.get('/api/reports?token=anything', headers=headers).status_code)
        self.assertEqual(0, self.calls)

    def test_archive_errors_do_not_disclose_exception_or_partial_data(self):
        def broken():
            raise ValueError('a private title and secret file path')
        app = viewer.create_app('https://luma-reports.example.ts.net', 'owner@example.com', broken)
        response = app.test_client().get('/api/reports', base_url='https://luma-reports.example.ts.net', headers={'Tailscale-User-Login': 'owner@example.com'})
        self.assertEqual(503, response.status_code)
        self.assertNotIn('private title', response.text)

    def test_production_rejects_insecure_or_non_tailnet_origins(self):
        for origin in ('http://luma-reports.example.ts.net', 'https://example.com',
                       'https://example.ts.net/path', 'https://user@example.ts.net',
                       'https://example.ts.net:444'):
            with self.assertRaises(ValueError):
                viewer.create_app(origin, 'owner@example.com', lambda: {})

    def test_browser_shell_contains_no_report_data_or_external_assets(self):
        response = self.get('/')
        self.assertEqual(200, response.status_code)
        self.assertNotIn('private title', response.text)
        self.assertNotIn('https://', response.text)
        self.assertEqual(0, self.calls)
        response.close()
        for path in ('/app.js', '/style.css', '/mark.svg'):
            with self.get(path) as asset:
                self.assertEqual(200, asset.status_code)


if __name__ == '__main__':
    unittest.main()

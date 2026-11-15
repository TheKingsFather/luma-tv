"""Owner-tool checks; generated keys and data only, no account or keyring access."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import uuid

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

spec = importlib.util.spec_from_file_location('private_reports', Path(__file__).with_name('private-reports.py'))
reports = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reports)


class OwnerToolTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.key = rsa.generate_private_key(public_exponent=65537, key_size=3072)

    def row(self):
        return {'version': 1, 'session': str(uuid.uuid4()), 'title': 'Generated fixture',
                'watched_ms': 12500, 'started_at_ms': 1000, 'updated_at_ms': 2000}

    def seal(self, row):
        aes, nonce = os.urandom(32), os.urandom(12)
        wrapped = self.key.public_key().encrypt(aes, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
        header = b'LUMARPT1' + struct.pack('>H', len(wrapped)) + wrapped + nonce
        encrypted = AESGCM(aes).encrypt(nonce, json.dumps(row).encode(), header)
        return header + struct.pack('>I', len(encrypted)) + encrypted

    def test_decrypts_and_rejects_tampering_wrong_owner_and_truncation(self):
        row = self.row()
        encrypted = self.seal(row)
        self.assertEqual(row, reports.decrypt_record(self.key, encrypted))
        with self.assertRaises(InvalidTag):
            reports.decrypt_record(self.key, encrypted[:-1] + bytes([encrypted[-1] ^ 1]))
        with self.assertRaises(ValueError):
            reports.decrypt_record(rsa.generate_private_key(public_exponent=65537, key_size=3072), encrypted)
        with self.assertRaises(ValueError):
            reports.decrypt_record(self.key, encrypted[:-1])

    def test_rejects_paths_secret_fields_and_invalid_durations(self):
        for field, value in [('session', '../escape'), ('watched_ms', -1), ('watched_ms', True), ('url', 'https://secret.invalid/token')]:
            row = self.row()
            row[field] = value
            with self.assertRaises(ValueError):
                reports.decrypt_record(self.key, self.seal(row))

    def test_java_ciphertext_interoperability(self):
        java = Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk')) / 'bin'
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            (folder / 'owner.der').write_bytes(reports.public_bytes(self.key))
            row = self.row()
            (folder / 'row.json').write_text(json.dumps(row))
            (folder / 'Interop.java').write_text('''import tv.luma.ReportCrypto; import java.nio.file.*;
public class Interop { public static void main(String[] args)throws Exception {
 Path p=Path.of(args[0]); Files.write(p.resolve("row.rpt"),ReportCrypto.seal(ReportCrypto.publicKey(Files.readAllBytes(p.resolve("owner.der"))),Files.readAllBytes(p.resolve("row.json")))); }}''')
            subprocess.run([str(java / 'javac'), '-d', str(folder), str(root / 'app/src/main/java/tv/luma/ReportCrypto.java'), str(folder / 'Interop.java')], check=True)
            subprocess.run([str(java / 'java'), '-cp', str(folder), 'Interop', str(folder)], check=True)
            self.assertEqual(row, reports.decrypt_record(self.key, (folder / 'row.rpt').read_bytes()))

    def test_private_atomic_storage(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'records/report.rpt'
            reports.private_write(path, b'ciphertext')
            reports.private_write(path, b'updated ciphertext')
            self.assertEqual(b'updated ciphertext', path.read_bytes())
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertEqual(0o700, path.parent.stat().st_mode & 0o777)
            self.assertEqual([path], list(path.parent.iterdir()))


if __name__ == '__main__':
    unittest.main()

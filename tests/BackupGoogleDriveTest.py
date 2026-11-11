import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('backup', Path(__file__).resolve().parents[1] / 'tools/backup_google_drive.py')
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)

class Response(io.BytesIO):
    def __enter__(self): return self
    def __exit__(self, *args): self.close()

class BackupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.archive = Path(self.tmp.name) / 'backup.zip'
        self.archive.write_bytes(b'PK\x03\x04example')
        self.credentials = json.dumps({'client_id':'client', 'client_secret':'secret', 'refresh_token':'refresh'})
    def tearDown(self): self.tmp.cleanup()
    def run_backup(self, fake):
        with patch.dict(os.environ, {'GOOGLE_DRIVE_CREDENTIALS': self.credentials}), patch.object(sys, 'argv', ['backup', str(self.archive)]), patch('urllib.request.urlopen', side_effect=fake):
            backup.main()
    def test_replaces_only_verified_file(self):
        calls = []
        def fake(req, timeout):
            calls.append(req)
            if len(calls)==1: data={'access_token':'access'}
            elif len(calls)==2: data={'id':backup.FILE_ID,'name':backup.NAME,'parents':[backup.FOLDER_ID]}
            else: data={'id':backup.FILE_ID,'size':str(self.archive.stat().st_size)}
            return Response(json.dumps(data).encode())
        self.run_backup(fake)
        self.assertEqual(len(calls), 3)
        self.assertEqual(calls[2].get_method(), 'PATCH')
        self.assertIn('/'+backup.FILE_ID+'?', calls[2].full_url)
        self.assertEqual(calls[2].data, self.archive.read_bytes())
    def test_moved_file_never_overwritten(self):
        calls=[]
        def fake(req,timeout):
            calls.append(req)
            data={'access_token':'access'} if len(calls)==1 else {'id':backup.FILE_ID,'name':backup.NAME,'parents':['other']}
            return Response(json.dumps(data).encode())
        with self.assertRaises(RuntimeError): self.run_backup(fake)
        self.assertEqual(len(calls),2)

if __name__ == '__main__': unittest.main()

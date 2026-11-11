import hashlib
import importlib.util
import io
import json
import os
import subprocess
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('backup_wif', ROOT / 'tools/backup_google_drive_wif.py')
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)

class BackupWifTest(unittest.TestCase):
    def setUp(self):
        self.old = os.getcwd()
        os.chdir(ROOT)
        self.sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    def tearDown(self):
        os.chdir(self.old)
    def test_shared_file_without_visible_parent_is_updated_and_verified(self):
        calls=[]
        def fake(url, token, *, method='GET', data=None, content_type=None):
            calls.append(method)
            if method=='GET':
                return {'id':backup.FILE_ID,'name':backup.NAME,'capabilities':{'canEdit':True}}
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                self.assertEqual(archive.read('BACKUP_COMMIT.txt'),(self.sha+'\n').encode())
                self.assertIsNone(archive.testzip())
            return {'id':backup.FILE_ID,'size':str(len(data)),'md5Checksum':hashlib.md5(data).hexdigest()}
        with patch.dict(os.environ,{'BACKUP_COMMIT':self.sha,'DRIVE_ACCESS_TOKEN':'test'}),patch.object(backup,'request',side_effect=fake):
            backup.main()
        self.assertEqual(calls,['GET','PATCH'])
    def test_missing_edit_permission_never_uploads(self):
        def fake(url, token, **kwargs):return {'id':backup.FILE_ID,'name':backup.NAME,'capabilities':{'canEdit':False}}
        with patch.dict(os.environ,{'BACKUP_COMMIT':self.sha,'DRIVE_ACCESS_TOKEN':'test'}),patch.object(backup,'request',side_effect=fake) as request:
            with self.assertRaises(RuntimeError):backup.main()
            self.assertEqual(request.call_count,1)

if __name__=='__main__':unittest.main()

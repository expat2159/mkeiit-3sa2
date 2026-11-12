import hashlib
import importlib.util
import io
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


def marker_zip(sha):
    data = io.BytesIO()
    with zipfile.ZipFile(data, 'w') as archive:
        archive.writestr('BACKUP_COMMIT.txt', sha + '\n')
    return data.getvalue()


class BackupWifTest(unittest.TestCase):
    def setUp(self):
        self.old_cwd = os.getcwd()
        os.chdir(ROOT)
        self.sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
        self.parent = subprocess.check_output(['git', 'rev-parse', 'HEAD^1'], text=True).strip()
        self.files = {backup.CURRENT_ID: marker_zip('a' * 40), backup.PREVIOUS_ID: marker_zip('b' * 40)}
        self.uploads = []
        self.no_edit = None
        self.fail_upload = None

    def tearDown(self):
        os.chdir(self.old_cwd)

    def fake_request(self, url, token, *, method='GET', data=None, content_type=None, binary=False):
        file_id = next(key for key in self.files if key in url)
        if method == 'PATCH':
            if file_id == self.fail_upload:
                raise RuntimeError('upload failed')
            self.files[file_id] = data
            self.uploads.append(file_id)
            return {'id': file_id, 'size': str(len(data)), 'md5Checksum': hashlib.md5(data).hexdigest()}
        if binary:
            return self.files[file_id]
        name = backup.CURRENT_NAME if file_id == backup.CURRENT_ID else backup.PREVIOUS_NAME
        return {'id': file_id, 'name': name, 'capabilities': {'canEdit': file_id != self.no_edit}}

    def run_backup(self, remote=None):
        with patch.dict(os.environ, {'BACKUP_COMMIT': self.sha, 'DRIVE_ACCESS_TOKEN': 'test'}), \
             patch.object(backup, 'remote_main', return_value=remote or self.sha), \
             patch.object(backup, 'request', side_effect=self.fake_request):
            backup.main()

    def test_updates_previous_first_and_keeps_exact_commits(self):
        self.run_backup()
        self.assertEqual(self.uploads, [backup.PREVIOUS_ID, backup.CURRENT_ID])
        for file_id, sha in [(backup.CURRENT_ID, self.sha), (backup.PREVIOUS_ID, self.parent)]:
            with zipfile.ZipFile(io.BytesIO(self.files[file_id])) as archive:
                self.assertIsNone(archive.testzip())
                self.assertEqual(archive.read('BACKUP_COMMIT.txt'), (sha + '\n').encode())
        self.run_backup()
        self.assertEqual(len(self.uploads), 2)

    def test_missing_permission_prevents_all_uploads(self):
        self.no_edit = backup.PREVIOUS_ID
        with self.assertRaises(RuntimeError):
            self.run_backup()
        self.assertEqual(self.uploads, [])

    def test_stale_run_does_not_write(self):
        self.run_backup(remote='f' * 40)
        self.assertEqual(self.uploads, [])

    def test_previous_upload_failure_preserves_current(self):
        original = self.files[backup.CURRENT_ID]
        self.fail_upload = backup.PREVIOUS_ID
        with self.assertRaises(RuntimeError):
            self.run_backup()
        self.assertEqual(self.files[backup.CURRENT_ID], original)
        self.assertEqual(self.uploads, [])

    def test_archive_matches_tracked_binary_and_permissions(self):
        entries = subprocess.check_output(['git', 'ls-tree', '-rz', '-r', self.sha]).split(b'\0')
        with zipfile.ZipFile(io.BytesIO(backup.create_archive(self.sha))) as archive:
            for entry in filter(None, entries):
                metadata, path = entry.split(b'\t', 1)
                mode, kind, blob = metadata.decode().split()
                self.assertEqual(kind, 'blob')
                name = 'mkeiit-3sa2/' + path.decode()
                self.assertEqual(archive.read(name), subprocess.check_output(['git', 'cat-file', 'blob', blob]))
                if mode in ('100644', '100755'):
                    self.assertEqual(bool(archive.getinfo(name).external_attr >> 16 & 0o111), mode == '100755')


if __name__ == '__main__':
    unittest.main()

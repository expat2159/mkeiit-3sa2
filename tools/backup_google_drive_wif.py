#!/usr/bin/env python3
"""Keep two existing Drive ZIPs at main and its first-parent commit using WIF."""
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

CURRENT_ID = '1cRZnfPlqfuQoTjGwqECaVvj2TGVIBRkL'
PREVIOUS_ID = '1ih2R9ZVqExZtHdtz9BgXj-knZru3isA5'
CURRENT_NAME = '3SA2-source-backup.zip'
PREVIOUS_NAME = '3SA2-source-previous.zip'
API = 'https://www.googleapis.com/drive/v3/files/'


def request(url, token, *, method='GET', data=None, content_type=None, binary=False):
    headers = {'Authorization': 'Bearer ' + token}
    if content_type:
        headers['Content-Type'] = content_type
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return response.read() if binary else json.load(response)
    except urllib.error.HTTPError as exc:
        # Never print response body or the token.
        raise RuntimeError('Google Drive returned HTTP ' + str(exc.code)) from None


def remote_main():
    result = subprocess.check_output(['git', 'ls-remote', '--exit-code', 'origin', 'refs/heads/main'], text=True, timeout=60)
    return result.split()[0]


def checked_item(file_id, name, token):
    item = request(API + file_id + '?fields=id,name,trashed,capabilities(canEdit)', token)
    if item.get('id') != file_id or item.get('name') != name or item.get('trashed') or not item.get('capabilities', {}).get('canEdit'):
        raise RuntimeError('The specified backup file is unavailable or cannot be edited: ' + name)


def archive_commit(file_id, token):
    data = request(API + file_id + '?alt=media', token, binary=True)
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            if archive.testzip() is not None:
                return None
            return archive.read('BACKUP_COMMIT.txt').decode('ascii').strip()
    except (zipfile.BadZipFile, KeyError, UnicodeDecodeError):
        return None


def create_archive(sha):
    with tempfile.TemporaryDirectory() as directory:
        target = Path(directory) / 'backup.zip'
        subprocess.run(['git', 'archive', '--format=zip', '--prefix=mkeiit-3sa2/', '-o', str(target), sha], check=True)
        with zipfile.ZipFile(target, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('BACKUP_COMMIT.txt', sha + '\n')
        with zipfile.ZipFile(target) as archive:
            if archive.testzip() is not None or archive.read('BACKUP_COMMIT.txt') != (sha + '\n').encode('ascii'):
                raise RuntimeError('ZIP integrity check failed')
        return target.read_bytes()


def upload(file_id, content, token):
    checksum = hashlib.md5(content).hexdigest()
    result = request('https://www.googleapis.com/upload/drive/v3/files/' + file_id + '?uploadType=media&fields=id,size,md5Checksum',
                     token, method='PATCH', data=content, content_type='application/zip')
    if result.get('id') != file_id or int(result.get('size', -1)) != len(content) or result.get('md5Checksum') != checksum:
        raise RuntimeError('Drive did not confirm the expected file and checksum')


def main():
    sha = os.environ.get('BACKUP_COMMIT', '')
    token = os.environ.get('DRIVE_ACCESS_TOKEN', '')
    if len(sha) != 40 or any(c not in '0123456789abcdef' for c in sha):
        raise RuntimeError('Invalid backup commit SHA')
    if not token:
        raise RuntimeError('Short-lived Drive access token is missing')
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD^{commit}'], text=True).strip()
    if actual != sha:
        raise RuntimeError('Checkout does not match the event commit')
    if remote_main() != sha:
        print('A newer main commit exists; skip this stale run')
        return
    try:
        parent = subprocess.check_output(['git', 'rev-parse', sha + '^1'], text=True, stderr=subprocess.DEVNULL).strip()
    except subprocess.CalledProcessError:
        raise RuntimeError('The repository has no previous commit') from None
    checked_item(CURRENT_ID, CURRENT_NAME, token)
    checked_item(PREVIOUS_ID, PREVIOUS_NAME, token)
    current_sha = archive_commit(CURRENT_ID, token)
    previous_sha = archive_commit(PREVIOUS_ID, token)
    if current_sha == sha and previous_sha == parent:
        print('Both backups are current:', sha, parent)
        return
    # Prepare and validate both before either remote write. Update previous first,
    # so a failure never destroys the last valid current backup.
    previous = create_archive(parent) if previous_sha != parent else None
    current = create_archive(sha) if current_sha != sha else None
    if remote_main() != sha:
        print('main changed while preparing backups; skip this stale run')
        return
    if previous is not None:
        upload(PREVIOUS_ID, previous, token)
        print('Previous backup:', parent)
    if current is not None:
        upload(CURRENT_ID, current, token)
        print('Current backup:', sha)


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('Backup failed:', type(exc).__name__, str(exc), file=sys.stderr)
        sys.exit(1)

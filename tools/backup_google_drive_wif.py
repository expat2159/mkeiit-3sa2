#!/usr/bin/env python3
"""Replace the existing Drive ZIP using a short-lived WIF access token."""
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

FILE_ID = '1cRZnfPlqfuQoTjGwqECaVvj2TGVIBRkL'
NAME = '3SA2-source-backup.zip'
API = 'https://www.googleapis.com/drive/v3/files/' + FILE_ID


def request(url, token, *, method='GET', data=None, content_type=None):
    headers = {'Authorization': 'Bearer ' + token}
    if content_type:
        headers['Content-Type'] = content_type
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        # Never print response body or the token.
        raise RuntimeError('Google Drive returned HTTP ' + str(exc.code)) from None


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
    item = request(API + '?fields=id,name,trashed,capabilities(canEdit)', token)
    if item.get('id') != FILE_ID or item.get('name') != NAME or item.get('trashed') or not item.get('capabilities', {}).get('canEdit'):
        raise RuntimeError('The specified backup file is unavailable or cannot be edited')
    with tempfile.TemporaryDirectory() as directory:
        target = Path(directory) / NAME
        subprocess.run(['git', 'archive', '--format=zip', '--prefix=mkeiit-3sa2/', '-o', str(target), sha], check=True)
        with zipfile.ZipFile(target, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('BACKUP_COMMIT.txt', sha + '\n')
        with zipfile.ZipFile(target) as archive:
            if archive.testzip() is not None or archive.read('BACKUP_COMMIT.txt') != (sha + '\n').encode('ascii'):
                raise RuntimeError('ZIP integrity check failed')
        content = target.read_bytes()
    checksum = hashlib.md5(content).hexdigest()
    result = request('https://www.googleapis.com/upload/drive/v3/files/' + FILE_ID + '?uploadType=media&fields=id,size,md5Checksum',
                     token, method='PATCH', data=content, content_type='application/zip')
    if result.get('id') != FILE_ID or int(result.get('size', -1)) != len(content) or result.get('md5Checksum') != checksum:
        raise RuntimeError('Drive did not confirm the expected file and checksum')
    print('Backup updated:', sha, 'bytes:', len(content), 'md5:', checksum)


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('Backup failed:', type(exc).__name__, str(exc), file=sys.stderr)
        sys.exit(1)

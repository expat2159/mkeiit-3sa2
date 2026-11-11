#!/usr/bin/env python3
"""Cloud Run job: keep one existing Drive file backed up to the exact public Git commit."""
import io
import json
import os
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

REPO = 'https://github.com/expat2159/mkeiit-3sa2.git'
FOLDER_ID = '1EKFKpF6HyLYdGLIS0hUn9hUNXbpLhSvX'
FILE_ID = '1cRZnfPlqfuQoTjGwqECaVvj2TGVIBRkL'
FILE_NAME = '3SA2-source-backup.zip'
SCOPE = 'https://www.googleapis.com/auth/drive'


def request(url, method='GET', data=None, headers=None, metadata=False):
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    # Cloud Run metadata must never go through a configured HTTP proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({})) if metadata else urllib.request.build_opener()
    try:
        with opener.open(req, timeout=120) as response:
            return response.read()
    except urllib.error.HTTPError as e:
        raise RuntimeError('HTTP ' + str(e.code) + ' from ' + urllib.parse.urlsplit(url).netloc) from None


def metadata_token():
    url = 'http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token?scopes=' + urllib.parse.quote(SCOPE, safe='')
    payload = json.loads(request(url, headers={'Metadata-Flavor': 'Google'}, metadata=True))
    return payload['access_token']


def drive_get(token, suffix):
    return request('https://www.googleapis.com/drive/v3/files/' + FILE_ID + suffix,
                   headers={'Authorization': 'Bearer ' + token})


def main():
    # Read one exact commit and fetch that exact object, rather than a moving branch.
    line = subprocess.check_output(['git', 'ls-remote', '--exit-code', REPO, 'refs/heads/main'], text=True, timeout=90)
    sha = line.split()[0]
    if len(sha) != 40 or any(c not in '0123456789abcdef' for c in sha):
        raise RuntimeError('Invalid Git commit SHA')
    token = metadata_token()
    metadata = json.loads(drive_get(token, '?fields=id,name,parents,trashed'))
    if metadata.get('id') != FILE_ID or metadata.get('name') != FILE_NAME or FOLDER_ID not in metadata.get('parents', []) or metadata.get('trashed'):
        raise RuntimeError('Backup file was moved, renamed, or removed; no file was changed')
    old = drive_get(token, '?alt=media')
    try:
        with zipfile.ZipFile(io.BytesIO(old)) as archive:
            old_sha = archive.read('BACKUP_COMMIT.txt').decode('ascii').strip()
            if old_sha == sha and archive.testzip() is None:
                print('Backup is current:', sha)
                return
    except (zipfile.BadZipFile, KeyError, UnicodeDecodeError):
        pass
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(['git', 'init', '--quiet', tmp], check=True, timeout=30)
        subprocess.run(['git', '-C', tmp, 'fetch', '--quiet', '--depth=1', REPO, sha], check=True, timeout=180)
        actual = subprocess.check_output(['git', '-C', tmp, 'rev-parse', 'FETCH_HEAD^{commit}'], text=True, timeout=30).strip()
        if actual != sha:
            raise RuntimeError('Fetched a different Git commit')
        archive_path = Path(tmp) / 'backup.zip'
        subprocess.run(['git', '-C', tmp, 'archive', '--format=zip', '--prefix=mkeiit-3sa2/', '-o', str(archive_path), sha], check=True, timeout=90)
        with zipfile.ZipFile(archive_path, 'a', zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('BACKUP_COMMIT.txt', sha + '\n')
        with zipfile.ZipFile(archive_path) as archive:
            if archive.testzip() is not None or archive.read('BACKUP_COMMIT.txt').decode('ascii') != sha + '\n':
                raise RuntimeError('Failed to verify ZIP')
        payload = archive_path.read_bytes()
    url = 'https://www.googleapis.com/upload/drive/v3/files/' + FILE_ID + '?uploadType=media&fields=id,size,modifiedTime'
    result = json.loads(request(url, method='PATCH', data=payload, headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/zip'}))
    if result.get('id') != FILE_ID or int(result.get('size', -1)) != len(payload):
        raise RuntimeError('Drive did not confirm the uploaded file')
    check = drive_get(token, '?alt=media')
    with zipfile.ZipFile(io.BytesIO(check)) as archive:
        if archive.testzip() is not None or archive.read('BACKUP_COMMIT.txt').decode('ascii') != sha + '\n':
            raise RuntimeError('Remote ZIP verification failed')
    print('Backup updated:', sha)


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        # Do not log response bodies, tokens, or credentials.
        print('Backup failed:', type(exc).__name__, str(exc), flush=True)
        raise SystemExit(1)

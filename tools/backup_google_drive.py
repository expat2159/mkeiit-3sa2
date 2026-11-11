#!/usr/bin/env python3
"""Replace the user's existing Drive ZIP. Credentials are read only from the environment."""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

FILE_ID = '1pmrREoUwDtd_t2y5M0QEYP-YMcjzp7U5'
FOLDER_ID = '1IZdoBcTYCAGOU1oItX39DlSCsKgxl_A-'
NAME = '3SA2-source-backup.zip'


def request(url, *, method='GET', data=None, headers=None):
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        # Do not print response bodies or credentials to workflow logs.
        raise RuntimeError('Google Drive returned HTTP ' + str(exc.code)) from None


def main():
    if len(sys.argv) != 2:
        raise RuntimeError('Usage: backup_google_drive.py ARCHIVE.zip')
    raw = os.environ.get('GOOGLE_DRIVE_CREDENTIALS', '')
    if not raw:
        raise RuntimeError('Add the GOOGLE_DRIVE_CREDENTIALS GitHub Actions secret and rerun this workflow.')
    try:
        creds = json.loads(raw)
        fields = {key: creds[key] for key in ('client_id', 'client_secret', 'refresh_token')}
        if not all(isinstance(value, str) and value for value in fields.values()):
            raise ValueError()
    except (KeyError, ValueError, TypeError):
        raise RuntimeError('The GOOGLE_DRIVE_CREDENTIALS secret has an invalid format.') from None
    fields['grant_type'] = 'refresh_token'
    token = request('https://oauth2.googleapis.com/token', method='POST', data=urllib.parse.urlencode(fields).encode('utf-8'))['access_token']
    headers = {'Authorization': 'Bearer ' + token}
    metadata = request('https://www.googleapis.com/drive/v3/files/' + FILE_ID + '?fields=id,name,parents,trashed', headers=headers)
    if metadata.get('name') != NAME or FOLDER_ID not in metadata.get('parents', []) or metadata.get('trashed'):
        raise RuntimeError('The backup file is missing or no longer in the selected folder.')
    archive = Path(sys.argv[1]).read_bytes()
    if not archive.startswith(b'PK\x03\x04'):
        raise RuntimeError('The source archive is not a ZIP file.')
    headers['Content-Type'] = 'application/zip'
    result = request('https://www.googleapis.com/upload/drive/v3/files/' + FILE_ID + '?uploadType=media&fields=id,modifiedTime,size', method='PATCH', data=archive, headers=headers)
    if result.get('id') != FILE_ID or int(result.get('size', -1)) != len(archive):
        raise RuntimeError('Drive did not confirm the uploaded backup.')
    print('Backup updated:', NAME, 'bytes:', len(archive), 'time:', result.get('modifiedTime', 'unknown'))


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('Backup failed:', type(exc).__name__, str(exc), file=sys.stderr)
        sys.exit(1)

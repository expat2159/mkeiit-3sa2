#!/usr/bin/env python3
"""Authorize a Google OAuth Desktop client and save GitHub Actions credentials locally."""
import base64
import hashlib
import http.server
import json
import os
import secrets
import sys
import urllib.parse
import urllib.request
import webbrowser
from pathlib import Path

class Callback(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
        self.server.result = query
        self.send_response(200)
        self.end_headers()
        self.wfile.write('You may close this tab and return to the terminal.'.encode())
    def log_message(self, *args):
        pass


def main():
    if len(sys.argv) != 2:
        raise RuntimeError('Usage: python authorize_google_drive.py CLIENT_JSON')
    client = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))['installed']
    server = http.server.HTTPServer(('127.0.0.1', 0), Callback)
    server.timeout = 300
    redirect = 'http://127.0.0.1:' + str(server.server_port) + '/'
    state = secrets.token_urlsafe(32)
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode('ascii')).digest()).decode('ascii').rstrip('=')
    params = {'client_id': client['client_id'], 'redirect_uri': redirect, 'response_type': 'code',
              'scope': 'https://www.googleapis.com/auth/drive', 'access_type': 'offline', 'prompt': 'consent', 'state': state, 'code_challenge': challenge, 'code_challenge_method': 'S256'}
    url = 'https://accounts.google.com/o/oauth2/v2/auth?' + urllib.parse.urlencode(params)
    print('Opening Google sign-in. If it does not open, visit this URL:')
    print(url)
    webbrowser.open(url)
    server.handle_request()
    result = getattr(server, 'result', {})
    server.server_close()
    if result.get('state', [''])[0] != state or 'code' not in result:
        raise RuntimeError('Authorization was cancelled or timed out.')
    fields = {'code': result['code'][0], 'client_id': client['client_id'], 'client_secret': client['client_secret'],
              'redirect_uri': redirect, 'grant_type': 'authorization_code', 'code_verifier': verifier}
    req = urllib.request.Request('https://oauth2.googleapis.com/token', data=urllib.parse.urlencode(fields).encode('utf-8'))
    with urllib.request.urlopen(req, timeout=30) as response:
        token = json.load(response)
    if not token.get('refresh_token'):
        raise RuntimeError('Google did not provide a refresh token; revoke access and authorize again.')
    output = {'client_id': client['client_id'], 'client_secret': client['client_secret'], 'refresh_token': token['refresh_token']}
    target = Path('google-drive-backup-credentials.json')
    fd = os.open(str(target), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as handle:
        json.dump(output, handle, ensure_ascii=False)
    print('Credentials saved to', target.resolve())
    print('Copy its contents to GitHub Actions secret GOOGLE_DRIVE_CREDENTIALS. Do not send it in chat or commit it.')


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('Authorization failed:', type(exc).__name__, str(exc), file=sys.stderr)
        sys.exit(1)

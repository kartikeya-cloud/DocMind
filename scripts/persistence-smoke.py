"""Verify a built JAR retains documents and sessions across a server restart.
Run from the project root: python scripts/persistence-smoke.py
"""
import json
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.request

project = Path(__file__).resolve().parents[1]
jar = project / 'runtime/docmind.jar'
if not jar.exists():
    jar = project / 'backend/target/docmind-1.0.0.jar'
base = 'http://127.0.0.1:18080/api'

def request(path, data=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    body = json.dumps(data).encode() if data is not None else None
    with urllib.request.urlopen(urllib.request.Request(base + path, body, headers), timeout=10) as response:
        raw = response.read()
        return json.loads(raw) if raw else None

def start(folder, log):
    process = subprocess.Popen(['java', '-jar', str(jar), '--server.port=18080'], cwd=folder, stdout=log, stderr=log)
    for _ in range(40):
        if process.poll() is not None:
            raise RuntimeError('Server exited. Inspect smoke-test logs.')
        try:
            if request('/health')['status'] == 'ok':
                return process
        except (OSError, ValueError):
            time.sleep(.5)
    process.terminate()
    raise RuntimeError('Server startup timed out.')

def stop(process):
    process.terminate()
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()

with tempfile.TemporaryDirectory(prefix='docmind-smoke-') as folder:
    with open(Path(folder) / 'server.log', 'w') as log:
        process = start(folder, log)
        try:
            session = request('/auth/register', {'name': 'Persistence tester', 'email': 'persist@example.com', 'password': 'persist-test-123'})
            token = session['token']
            boundary = 'docmind-smoke-boundary'
            body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="persist.txt"\r\nContent-Type: text/plain\r\n\r\nPersistence verification: the roadmap launches in November.\r\n--{boundary}--\r\n').encode()
            req = urllib.request.Request(base + '/documents', body, {'Content-Type': 'multipart/form-data; boundary=' + boundary, 'Authorization': 'Bearer ' + token})
            with urllib.request.urlopen(req, timeout=15) as response:
                document = json.load(response)
        finally:
            stop(process)
        process = start(folder, log)
        try:
            assert request('/auth/me', token=token)['email'] == 'persist@example.com'
            documents = request('/documents', token=token)
            assert len(documents) == 1 and documents[0]['id'] == document['id']
            hits = request('/search?q=November', token=token)
            assert hits and 'November' in hits[0]['content']
        finally:
            stop(process)
print('PASS: account session, indexed document and search results persist across restart.')

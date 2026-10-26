#!/usr/bin/env python3
"""Small dependency-free, fail-closed release verifier/publisher."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

APP_ID = 'us.josemancharo.voicerecorderqoa'
BUILD_TOOLS_VERSION = '36.0.0'

def plan(number):
    number = int(number)
    if not 1 <= number <= 2_099_999_000:
        raise ValueError('Invalid workflow run number')
    return f'2.0.{number}', 1000 + number

def run(*args):
    return subprocess.check_output(args, text=True).strip()

def api(path, *args):
    return json.loads(run('gh', 'api', path, *args))

def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def certificate_digest(output):
    # JVM launch warnings can be printed to stdout before keytool's PEM. Hash
    # only the one certificate block, never arbitrary banner/warning text.
    import base64
    blocks = re.findall(r'-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\s]+)-----END CERTIFICATE-----', output)
    if len(blocks) != 1 or output.count('-----BEGIN CERTIFICATE-----') != 1:
        raise ValueError('Expected exactly one exported public certificate')
    der = base64.b64decode(re.sub(r'\s+', '', blocks[0]), validate=True)
    if not der:
        raise ValueError('Exported public certificate is empty')
    return hashlib.sha256(der).hexdigest()


def signer_digest(output):
    matches = re.findall(r'^Signer #([0-9]+) certificate SHA-256 digest:\s*([0-9a-fA-F:]+)\s*$', output, re.MULTILINE)
    if len(matches) != 1 or matches[0][0] != '1':
        raise ValueError('Expected exactly one APK signer certificate SHA-256 digest')
    value = matches[0][1].replace(':', '').lower()
    if not re.fullmatch(r'[0-9a-f]{64}', value):
        raise ValueError('Malformed APK signer certificate SHA-256 digest')
    return value


def verify(apk, code, version):
    # Match the explicitly installed workflow tools, not a runner-image preview.
    root = Path(os.environ['ANDROID_HOME']) / 'build-tools' / BUILD_TOOLS_VERSION
    if not root.is_dir():
        raise ValueError(f'Required Android build-tools {BUILD_TOOLS_VERSION} not installed')
    signature = run(str(root / 'apksigner'), 'verify', '--verbose', '--print-certs', apk)
    badging = run(str(root / 'aapt'), 'dump', 'badging', apk)
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not package or package.groups() != (APP_ID, str(code), version):
        raise ValueError('Unexpected APK identity/version')
    if 'application-debuggable' in badging or "android.permission.INTERNET" in badging:
        raise ValueError('Debuggable or internet-enabled release refused')
    cert = run('keytool', '-exportcert', '-rfc', '-keystore', os.environ['KEYSTORE'], '-storepass:env', 'STORE_PASSWORD', '-alias', os.environ['KEY_ALIAS'])
    expected = certificate_digest(cert)
    actual = signer_digest(signature)
    if actual != expected:
        # Public certificate fingerprints are safe diagnostics; never log the
        # keystore, password, private key, alias, or raw subprocess output.
        raise ValueError(f'Signing certificate mismatch (build-tools {BUILD_TOOLS_VERSION}; expected public SHA256 {expected}; APK public SHA256 {actual})')
    print(f'Verified {APP_ID}, {version} ({code}), signing certificate SHA256 {expected}')

def publish(repo, tag, commit, assets):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
        raise ValueError('Invalid repository name')
    if not re.fullmatch(r'v2\.0\.[1-9][0-9]*', tag) or not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('Invalid release identity')
    files = sorted(Path(assets).iterdir())
    if len(files) != 2 or not any(p.name == 'SHA256SUMS' for p in files) or sum(p.suffix == '.apk' for p in files) != 1:
        raise ValueError('Expected one APK plus SHA256SUMS')
    releases = api(f'repos/{repo}/releases?per_page=100')
    release = next((r for r in releases if r['tag_name'] == tag), None)
    # Check existing tag independently; never attach new binaries to another source revision.
    refs = api(f'repos/{repo}/git/matching-refs/tags/{tag}')
    ref = next((r for r in refs if r['ref'] == f'refs/tags/{tag}'), None)
    if ref and (ref['object']['type'] != 'commit' or ref['object']['sha'] != commit):
        raise ValueError('Release tag points at another revision')
    if release and release['target_commitish'] != commit:
        raise ValueError('Existing release target mismatch')
    if not release:
        release = api(f'repos/{repo}/releases', '-X', 'POST', '-f', f'tag_name={tag}', '-f', f'target_commitish={commit}', '-f', f'name=Voice Recorder QOA {tag}', '-F', 'draft=true', '-F', 'generate_release_notes=true', '-f', 'body=Offline QOA voice recorder. Install the APK directly or follow this repository in Obtainium. Verify SHA256SUMS before installing. This app uses a separate identifier from Fossify Voice Recorder.')
    existing = {a['name']: a for a in api(f"repos/{repo}/releases/{release['id']}/assets")}
    for path in files:
        if path.name not in existing:
            if not release['draft']:
                raise ValueError('Published release is missing an asset; refusing mutation')
            from urllib.parse import quote
            # Upload by numeric ID, avoiding draft-tag indexing races. Never follow
            # a redirect while carrying the workflow token to an upload endpoint.
            import urllib.request
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self, req, fp, code, msg, headers, newurl):
                    return None
            with path.open('rb') as body:
                request = urllib.request.Request(
                    f"https://uploads.github.com/repos/{repo}/releases/{release['id']}/assets?name={quote(path.name)}",
                    data=body, method='POST', headers={
                        'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                        'Content-Type': 'application/octet-stream',
                        'Content-Length': str(path.stat().st_size),
                        'Accept': 'application/vnd.github+json',
                        'User-Agent': 'Voice-Recorder-QOA-release',
                    })
                with urllib.request.build_opener(NoRedirect).open(request, timeout=180) as response:
                    if response.status != 201:
                        raise ValueError('Asset upload did not succeed')
    # Download and hash all remote assets before making the draft public, including retry paths.
    with tempfile.TemporaryDirectory() as directory:
        remote = {a['name']: a for a in api(f"repos/{repo}/releases/{release['id']}/assets")}
        if set(remote) != {p.name for p in files}:
            raise ValueError('Unexpected remote asset set')
        for path in files:
            with open(Path(directory) / path.name, 'wb') as output:
                subprocess.run(['gh', 'api', f"repos/{repo}/releases/assets/{remote[path.name]['id']}", '-H', 'Accept: application/octet-stream'], check=True, stdout=output)
            if digest(path) != digest(Path(directory) / path.name):
                raise ValueError(f'Remote asset mismatch: {path.name}')
    later = any(re.fullmatch(r'v2\.0\.[1-9][0-9]*', r['tag_name']) and int(r['tag_name'].split('.')[-1]) > int(tag.split('.')[-1]) and not r['draft'] for r in releases)
    if release['draft']:
        api(f"repos/{repo}/releases/{release['id']}", '-X', 'PATCH', '-F', 'draft=false', '-f', f'make_latest={str(not later).lower()}')
    print(f'https://github.com/{repo}/releases/tag/{tag}')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('plan'); p.add_argument('--run-number', required=True)
    p = sub.add_parser('verify'); p.add_argument('apk'); p.add_argument('--code', required=True); p.add_argument('--version', required=True)
    p = sub.add_parser('publish'); p.add_argument('--repo', required=True); p.add_argument('--tag', required=True); p.add_argument('--commit', required=True); p.add_argument('--assets', required=True)
    args = parser.parse_args()
    if args.command == 'plan':
        version, code = plan(args.run_number); print(f'version={version}\ncode={code}')
    elif args.command == 'verify': verify(args.apk, args.code, args.version)
    else: publish(args.repo, args.tag, args.commit, args.assets)

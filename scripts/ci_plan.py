"""Fail-safe CI selection and conservative reuse of an identical, tested PR tree.

Reads only a bounded JSON tree record, never PR binaries or credentials. API/metadata uncertainty runs all gates.
Only an internal PR merged as this commit, identical tree, and successful named
jobs in this workflow can avoid repeating expensive validation on main.
"""
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile
from urllib.request import Request, urlopen

PROFILE = 'RECORDER'
WORKFLOW = 'android.yml' if PROFILE == 'SPRITE' else 'release.yml'
REQUIRED_JOBS = {'test', 'android', 'android-interaction'} if PROFILE == 'SPRITE' else {'checks', 'device-checks'}
SHA = re.compile(r'^[0-9a-f]{40}$')


def api(path):
    headers = {'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'}
    if os.environ.get('GH_TOKEN'):
        headers['Authorization'] = 'Bearer ' + os.environ['GH_TOKEN']
    with urlopen(Request('https://api.github.com/' + path, headers=headers), timeout=15) as response:
        return json.load(response)


def download_record(path):
    # gh handles authenticated GitHub archive redirects without forwarding tokens
    # to arbitrary hosts. Never extract or execute artifact contents.
    data = subprocess.check_output(['gh', 'api', path], timeout=30)
    if len(data) > 16384:
        raise ValueError('Oversized validation record archive')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if archive.namelist() != ['ci-tree.json'] or archive.getinfo('ci-tree.json').file_size > 4096:
            raise ValueError('Invalid validation record archive')
        return json.loads(archive.read('ci-tree.json'))


def reusable_run(repo, sha, tree, get=api, download=download_record):
    if not SHA.fullmatch(sha) or not SHA.fullmatch(tree):
        return None
    prefix = 'repos/' + repo
    for summary in get(prefix + '/commits/' + sha + '/pulls?per_page=100'):
        pr = get(prefix + '/pulls/' + str(int(summary['number'])))
        head = pr.get('head', {})
        if (not pr.get('merged_at') or pr.get('merge_commit_sha') != sha
                or pr.get('base', {}).get('ref') != 'main'
                or pr.get('base', {}).get('repo', {}).get('full_name') != repo
                or head.get('repo', {}).get('full_name') != repo):
            continue
        head_sha = head.get('sha', '')
        if not SHA.fullmatch(head_sha):
            continue
        commit = get(prefix + '/git/commits/' + head_sha)
        if commit.get('tree', {}).get('sha') != tree:
            continue
        runs = get(prefix + '/actions/workflows/' + WORKFLOW + '/runs?event=pull_request&head_sha=' + head_sha + '&per_page=100')
        for run in runs.get('workflow_runs', []):
            if (run.get('event') != 'pull_request' or run.get('head_sha') != head_sha
                    or run.get('head_repository', {}).get('full_name') != repo
                    or run.get('path') != '.github/workflows/' + WORKFLOW
                    or run.get('status') != 'completed' or run.get('conclusion') != 'success'):
                continue
            jobs = get(prefix + '/actions/runs/' + str(int(run['id'])) + '/jobs?per_page=100')
            named_jobs = {name: [j for j in jobs.get('jobs', []) if j.get('name') == name]
                          for name in REQUIRED_JOBS}
            if any(len(matches) != 1 or matches[0].get('status') != 'completed'
                   or matches[0].get('conclusion') != 'success'
                   for matches in named_jobs.values()):
                continue
            artifacts = get(prefix + '/actions/runs/' + str(int(run['id'])) + '/artifacts?name=validated-tree&per_page=100')
            for artifact in artifacts.get('artifacts', []):
                if (artifact.get('name') != 'validated-tree' or artifact.get('expired')
                        or not 0 < artifact.get('size_in_bytes', 0) <= 16384):
                    continue
                record = download(prefix + '/actions/artifacts/' + str(int(artifact['id'])) + '/zip')
                if record == {'schema': 1, 'repository': repo, 'head_sha': head_sha,
                              'tree': tree, 'run_id': str(run['id'])}:
                    return str(run['id'])
    return None


def classify(paths):
    # Only known documentation/metadata is exempt. Unknown/new code runs all gates.
    root_docs = {'README.md', 'CHANGELOG.md', 'RELEASE_NOTES.md', 'CONTRIBUTING.md',
                 'SECURITY.md', 'CODE_OF_CONDUCT.md', 'LICENSE', 'LICENSE.md'}
    docs = lambda p: p in root_docs or (p.startswith(('docs/', 'fastlane/metadata/'))
                                      and Path(p).suffix.lower() in {'.md', '.txt', '.rst', '.png', '.jpg', '.jpeg'})
    relevant = [p for p in paths if not docs(p)]
    heavy = bool(relevant)
    interop = heavy
    if PROFILE == 'SPRITE':
        # Pure UI layout changes do not alter file codecs or brush parity.
        ui = {'lib/main.dart', 'lib/color_picker.dart', 'lib/responsive_editor_shell.dart',
              'lib/document_tabs.dart', 'lib/visual_tag_editor.dart', 'lib/frame_thumbnails.dart',
              'lib/eraser_icon.dart', 'lib/palette_editor.dart'}
        interop = any(p not in ui and not p.startswith('test/') for p in relevant)
    return {'native': heavy, 'device': heavy, 'interop': interop, 'linux': heavy}


def plan(event_name, event, sha, ref, repo, tree, get=api, diff=None, download=download_record):
    result = dict.fromkeys(('native', 'device', 'interop', 'linux'), True)
    result['reused_run'] = ''
    if event_name == 'workflow_dispatch':
        return result  # Manual dispatch is deliberately the full suite.
    if event_name == 'pull_request':
        pr = event['pull_request']
        base, head = pr['base']['sha'], pr['head']['sha']
        if not SHA.fullmatch(base) or not SHA.fullmatch(head):
            raise ValueError('Invalid diff commit')
        paths = diff(base, head) if diff else subprocess.check_output(
            ['git', 'diff', '--name-only', base + '...' + head], text=True).splitlines()
        return {**classify(paths), 'reused_run': ''}
    if event_name == 'push' and ref == 'refs/heads/main':
        run = reusable_run(repo, sha, tree, get, download)
        if run:
            result.update(native=False, device=False, interop=False, reused_run=run)
        # Linux release packages are always built from this main commit.
    return result


def main():
    result = dict.fromkeys(('native', 'device', 'interop', 'linux'), True)
    result['reused_run'] = ''
    try:
        event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
        tree = subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], text=True).strip()
        result = plan(os.environ['GITHUB_EVENT_NAME'], event, os.environ['GITHUB_SHA'],
                      os.environ['GITHUB_REF'], os.environ['GITHUB_REPOSITORY'], tree)
    except Exception as error:
        # No secrets, token, API response or exception URL are written to logs.
        print('CI metadata unavailable (' + type(error).__name__ + '); running full validation.', file=sys.stderr)
    for key, value in result.items():
        print(key + '=' + (str(value).lower() if isinstance(value, bool) else value))
    if result['reused_run']:
        print('Reusing expensive validation from identical-tree PR run ' + result['reused_run'], file=sys.stderr)


def write_record():
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    head = event.get('pull_request', {}).get('head', {}).get('sha', os.environ['GITHUB_SHA'])
    tree = subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], text=True).strip()
    record = {'schema': 1, 'repository': os.environ['GITHUB_REPOSITORY'],
              'head_sha': head, 'tree': tree, 'run_id': os.environ['GITHUB_RUN_ID']}
    Path('ci-tree.json').write_text(json.dumps(record) + '\n')


if __name__ == '__main__':
    write_record() if sys.argv[1:] == ['record'] else main()

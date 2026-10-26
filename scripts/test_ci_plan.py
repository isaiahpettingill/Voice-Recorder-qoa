import unittest
import io
import json
import os
import tempfile
import zipfile
from contextlib import redirect_stdout, redirect_stderr
from pathlib import Path
from unittest.mock import patch
from copy import deepcopy
import ci_plan as ci


class CiPlanTests(unittest.TestCase):
    def setUp(self):
        self.repo = 'owner/app'
        self.sha, self.head, self.tree = 'a' * 40, 'b' * 40, 'c' * 40
        self.pr = {'merged_at': '2026-10-26', 'merge_commit_sha': self.sha,
                   'base': {'ref': 'main', 'repo': {'full_name': self.repo}},
                   'head': {'sha': self.head, 'repo': {'full_name': self.repo}}}
        self.run = {'id': 12, 'event': 'pull_request', 'head_sha': self.head,
                    'head_repository': {'full_name': self.repo},
                    'path': '.github/workflows/' + ci.WORKFLOW,
                    'status': 'completed', 'conclusion': 'success'}
        self.jobs = [{'name': n, 'status': 'completed', 'conclusion': 'success'} for n in ci.REQUIRED_JOBS]
        self.actual_tree = self.tree
        self.record = {'schema': 1, 'repository': self.repo, 'head_sha': self.head,
                       'tree': self.tree, 'run_id': '12'}
        self.artifacts = [{'id': 99, 'name': 'validated-tree', 'expired': False, 'size_in_bytes': 300}]

    def get(self, path):
        if '/commits/' in path and '/pulls?' in path: return [{'number': 7}]
        if '/pulls/7' in path: return self.pr
        if '/git/commits/' in path: return {'tree': {'sha': self.actual_tree}}
        if '/workflows/' in path: return {'workflow_runs': [self.run]}
        if '/jobs?' in path: return {'jobs': self.jobs}
        if '/artifacts?' in path: return {'artifacts': self.artifacts}
        raise AssertionError(path)

    def reuse(self):
        return ci.reusable_run(self.repo, self.sha, self.tree, self.get, download=lambda _: self.record)

    def test_identical_internal_success_reused(self):
        self.assertEqual(self.reuse(), '12')

    def test_missing_actual_test_tree_record_reruns(self):
        self.artifacts = []
        self.assertIsNone(self.reuse())

    def test_different_actual_test_tree_reruns(self):
        self.record['tree'] = 'd' * 40
        self.assertIsNone(self.reuse())

    def test_record_identity_must_match(self):
        for key in ('repository', 'head_sha', 'run_id', 'schema'):
            with self.subTest(key=key):
                old = deepcopy(self.record)
                self.record[key] = 'wrong'
                self.assertIsNone(self.reuse())
                self.record = old

    def test_expired_record_reruns(self):
        self.artifacts[0]['expired'] = True
        self.assertIsNone(self.reuse())

    def test_different_tree_reruns(self):
        self.actual_tree = 'd' * 40
        self.assertIsNone(self.reuse())

    def test_fork_not_trusted(self):
        self.pr['head']['repo']['full_name'] = 'fork/app'
        self.assertIsNone(self.reuse())

    def test_not_this_merge_reruns(self):
        self.pr['merge_commit_sha'] = 'e' * 40
        self.assertIsNone(self.reuse())

    def test_skipped_job_not_validation(self):
        self.jobs[0]['conclusion'] = 'skipped'
        self.assertIsNone(self.reuse())

    def test_duplicate_job_name_is_ambiguous(self):
        self.jobs.append(deepcopy(self.jobs[0]))
        self.assertIsNone(self.reuse())

    def test_missing_job_reruns(self):
        self.jobs = []
        self.assertIsNone(self.reuse())

    def test_wrong_workflow_event_head_or_conclusion_reruns(self):
        for key, value in [('path', '.github/workflows/other.yml'), ('event', 'push'),
                           ('head_sha', 'd'*40), ('status', 'in_progress'), ('conclusion', 'failure')]:
            with self.subTest(key=key):
                old = deepcopy(self.run)
                self.run[key] = value
                self.assertIsNone(self.reuse())
                self.run = old

    def test_docs_only_skip_expensive(self):
        result = ci.classify(['README.md', 'docs/testing.md'])
        self.assertFalse(any(result.values()))

    def test_unknown_and_workflow_changes_run_all(self):
        for path in ['new-platform/source.cc', '.github/workflows/release.yml', 'scripts/ci_plan.py',
                     'docs/run.py', 'app/assets/prompt.md', 'fastlane/metadata/hook.sh']:
            self.assertTrue(all(ci.classify([path]).values()))

    def test_manual_always_full(self):
        p = ci.plan('workflow_dispatch', {}, self.sha, 'refs/heads/main', self.repo, self.tree, self.get, download=lambda _: self.record)
        self.assertTrue(p['device'])
        self.assertEqual(p['reused_run'], '')

    def test_direct_push_no_associated_pr_full(self):
        p = ci.plan('push', {}, self.sha, 'refs/heads/main', self.repo, self.tree, lambda _: [])
        self.assertTrue(p['native'])
        self.assertTrue(p['device'])

    def test_main_reuse_still_builds_linux(self):
        p = ci.plan('push', {}, self.sha, 'refs/heads/main', self.repo, self.tree, self.get, download=lambda _: self.record)
        self.assertFalse(p['device'])
        self.assertFalse(p['native'])
        self.assertTrue(p['linux'])
        self.assertEqual(p['reused_run'], '12')

    def test_pr_docs_diff(self):
        event = {'pull_request': {'base': {'sha': self.sha}, 'head': {'sha': self.head}}}
        p = ci.plan('pull_request', event, self.head, 'refs/pull/7/merge', self.repo, self.tree,
                    diff=lambda *_: ['README.md'])
        self.assertFalse(p['device'])

    def test_ui_only_parser_not_needed(self):
        if ci.PROFILE == 'SPRITE':
            self.assertFalse(ci.classify(['lib/main.dart', 'test/widget_test.dart'])['interop'])
            self.assertTrue(ci.classify(['lib/engine/aseprite.dart'])['interop'])


class RecordArchiveTests(unittest.TestCase):
    def archive(self, entries):
        out = io.BytesIO()
        with zipfile.ZipFile(out, 'w') as archive:
            for name, contents in entries:
                archive.writestr(name, contents)
        return out.getvalue()

    def test_json_record_only(self):
        data = self.archive([('ci-tree.json', '{"schema": 1}')])
        with patch.object(ci.subprocess, 'check_output', return_value=data):
            self.assertEqual(ci.download_record('repos/owner/app/actions/artifacts/1/zip'), {'schema': 1})

    def test_malformed_oversized_and_extra_entries_rejected(self):
        values = [b'not a zip', b'a' * 16385,
                  self.archive([('ci-tree.json', 'a' * 4097)]),
                  self.archive([('ci-tree.json', '{}'), ('extra', 'x')]),
                  self.archive([('../ci-tree.json', '{}')]),
                  self.archive([('ci-tree.json', 'not json')])]
        for value in values:
            with self.subTest(size=len(value)), patch.object(ci.subprocess, 'check_output', return_value=value):
                with self.assertRaises((ValueError, zipfile.BadZipFile)):
                    ci.download_record('repos/owner/app/actions/artifacts/1/zip')

    def test_main_metadata_failure_runs_all_gates(self):
        with tempfile.TemporaryDirectory() as directory:
            event = Path(directory) / 'event.json'; event.write_text('{}')
            env = {'GITHUB_EVENT_PATH': str(event), 'GITHUB_EVENT_NAME': 'push',
                   'GITHUB_SHA': 'a' * 40, 'GITHUB_REF': 'refs/heads/main',
                   'GITHUB_REPOSITORY': 'owner/app'}
            output, errors = io.StringIO(), io.StringIO()
            with patch.dict(os.environ, env), patch.object(ci.subprocess, 'check_output', return_value='b'*40), \
                 patch.object(ci, 'plan', side_effect=RuntimeError('PRIVATE RESPONSE')), \
                 redirect_stdout(output), redirect_stderr(errors):
                ci.main()
            for gate in ('native', 'device', 'interop', 'linux'):
                self.assertIn(gate + '=true', output.getvalue())
            self.assertNotIn('PRIVATE RESPONSE', errors.getvalue())


if __name__ == '__main__':
    unittest.main()

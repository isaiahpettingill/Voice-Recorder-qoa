import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import release


class ReleaseTest(unittest.TestCase):
    def test_monotonic_versions(self):
        self.assertEqual(release.plan(1), ('2.0.1', 1001))
        self.assertEqual(release.plan(52), ('2.0.52', 1052))
        for number in (0, -1, 2100000001):
            with self.assertRaises(ValueError): release.plan(number)

    def test_invalid_release_identity(self):
        with self.assertRaises(ValueError): release.publish('owner/repo', 'bad', 'a' * 40, 'missing')

    def test_mismatched_tag_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'app.apk').write_bytes(b'apk')
            Path(directory, 'SHA256SUMS').write_text('test')
            responses = [[], [{'ref': 'refs/tags/v2.0.1', 'object': {'type': 'commit', 'sha': 'b' * 40}}]]
            with patch.object(release, 'api', side_effect=responses):
                with self.assertRaisesRegex(ValueError, 'another revision'):
                    release.publish('owner/repo', 'v2.0.1', 'a' * 40, directory)

    def test_missing_or_extra_assets_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError): release.publish('owner/repo', 'v2.0.1', 'a' * 40, directory)

    def test_digest_streaming(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory, 'file'); p.write_bytes(b'abc')
            self.assertEqual(release.digest(p), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')


class PublishTest(unittest.TestCase):
    def test_existing_published_release_is_verified_without_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'app.apk').write_bytes(b'apk')
            Path(directory, 'SHA256SUMS').write_bytes(b'hash')
            item = {'id': 5, 'tag_name': 'v2.0.1', 'target_commitish': 'a' * 40, 'draft': False}
            assets = [{'name': 'app.apk', 'id': 1}, {'name': 'SHA256SUMS', 'id': 2}]
            responses = [[item], [{'ref': 'refs/tags/v2.0.1', 'object': {'type': 'commit', 'sha': 'a' * 40}}], assets, assets]
            def download(args, check, stdout):
                stdout.write(b'apk' if args[2].endswith('/1') else b'hash')
            with patch.object(release, 'api', side_effect=responses) as api, patch.object(release.subprocess, 'run', side_effect=download):
                release.publish('owner/repo', 'v2.0.1', 'a' * 40, directory)
                self.assertTrue(all('-X' not in c.args for c in api.call_args_list))

    def test_existing_asset_mismatch_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'app.apk').write_bytes(b'apk')
            Path(directory, 'SHA256SUMS').write_bytes(b'hash')
            item = {'id': 5, 'tag_name': 'v2.0.1', 'target_commitish': 'a' * 40, 'draft': True}
            assets = [{'name': 'app.apk', 'id': 1}, {'name': 'SHA256SUMS', 'id': 2}]
            responses = [[item], [], assets, assets]
            def download(args, check, stdout): stdout.write(b'wrong')
            with patch.object(release, 'api', side_effect=responses), patch.object(release.subprocess, 'run', side_effect=download):
                with self.assertRaisesRegex(ValueError, 'mismatch'):
                    release.publish('owner/repo', 'v2.0.1', 'a' * 40, directory)

class ApkTest(unittest.TestCase):
    def check_apk(self, badging, digest='ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'build-tools', '36.0.0').mkdir(parents=True)
            env = {'ANDROID_HOME': directory, 'KEYSTORE': 'not-read.p12', 'KEY_ALIAS': 'test'}
            outputs = [f'Signer #1 certificate SHA-256 digest: {digest}', badging, '-----BEGIN CERTIFICATE-----\nYWJj\n-----END CERTIFICATE-----']
            with patch.dict(release.os.environ, env), patch.object(release, 'run', side_effect=outputs):
                release.verify('not-read.apk', 1001, '2.0.1')

    def test_valid_release_identity(self):
        self.check_apk("package: name='us.josemancharo.voicerecorderqoa' versionCode='1001' versionName='2.0.1'")

    def test_debug_and_internet_refused(self):
        identity = "package: name='us.josemancharo.voicerecorderqoa' versionCode='1001' versionName='2.0.1'"
        for extra in ['application-debuggable', 'android.permission.INTERNET']:
            with self.assertRaises(ValueError): self.check_apk(identity + '\n' + extra)

    def test_wrong_identity_or_certificate_refused(self):
        with self.assertRaises(ValueError): self.check_apk("package: name='org.fossify.voicerecorder' versionCode='21' versionName='1.7.4'")
        with self.assertRaises(ValueError): self.check_apk("package: name='us.josemancharo.voicerecorderqoa' versionCode='1001' versionName='2.0.1'", '0' * 64)

if __name__ == '__main__': unittest.main()

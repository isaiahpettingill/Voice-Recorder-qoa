# Fast CI and full validation

Unit tests, lint, native voice-library regressions (when present) and release-tool
checks remain available and required. Slow device tests run for application or
unknown file changes. Documentation-only PRs skip the device job.

After merging an internal PR, the main workflow may reuse device validation only
when this workflow's `checks` and `device-checks` jobs succeeded for that exact PR
head and a `validated-tree` artifact records the actual synthetic-merge checkout
Git tree equal to main. The PR must have merged as this main commit and both its
head tree and recorded tested tree must match. Forks, direct pushes, missing or
expired artifacts, metadata failures and failed/skipped required jobs run device
tests normally. No downloaded PR APK is signed or published.

Main no longer builds an unsigned minified release and immediately rebuilds it
signed: it keeps unit/lint/debug validation, then builds and verifies the signed
minified APK once. PRs still check unsigned minified packaging. APK certificate,
package ID, version, native architecture, permissions and checksum checks are not
relaxed. Only explicitly planned device skips can pass the release dependency
gate; failed or canceled jobs cannot.

**Actions → Build and release → Run workflow** forces all suites. On main it also
uses the existing signed-release flow; do not use it as a read-only status check.
Superseded PR runs are canceled, but main release jobs are never automatically
canceled.

## Measured baseline

Run [40354670868](https://github.com/isaiahpettingill/Voice-Recorder-qoa/actions/runs/40354670868)
on 2026-10-26 spent 240s in validation/assembly, 261s in the emulator, then 170s
building the signed release. Reusing device results and eliminating duplicate
minification should reduce the main critical path; exact savings are not measured
yet and depend on native builds and caches. Older runs without explicit tree
records are intentionally ineligible for reuse.

# Tempdoc 906 publication verification — 2026-09-06

Candidate: codex/906-publish, product HEAD 16bdb8254, base b96cd9998.
The caught-up source includes the async classification fix. Later commits only
organize the tempdoc and record evidence.

| Check | Result | Local evidence log |
| --- | --- | --- |
| gradlew build -x test plus Head/Worker installDist | PASS, includes integration checks | 906-publish-build.log |
| gradlew test checkLicense --no-configuration-cache --no-parallel | PASS, 3m22s | 906-publish-test-license.log |
| npm --prefix modules/ui-web run typecheck | PASS | 906-publish-typecheck.log |
| npm --prefix modules/ui-web run test:unit:run | 472 files, 6,333 tests pass | 906-publish-ui-tests.log |
| node scripts/ci/run-ui-web-gates.mjs | 27/27 pass | 906-publish-ui-gates.log |
| python -m pytest scripts/jseval/tests -q -p no:cacheprovider | 3,094 passed, 12 skipped, 81 warnings, 854.75s | 906-publish-pytest.log |
| jseval ui-shot health-completion / library-ingestion / search-failure --fixtures | All pass; zero captured axe violations and document overflow | 906-publish-ui/*.measure.json |
| jseval ui-a11y-gate | Exit 0, no new violations against baseline | 906-publish-a11y-recovery.log |
| node scripts/ci/regen-all.mjs --check | All eight sets match | 906-publish-regen-final.log |
| gitleaks git with origin/main..HEAD | No leaks | 906-publish-secrets.log |

Other passing checks: run-publish-preflight --check, check-tempdoc-numbers,
check-tempdoc-size, llmstxt-generate --check, skills-sync --check,
verify-canonical-doc-links, module-deps --check-canonical,
verify-runtime-config-matrix, docs-validate, check-ps1-warning-comments,
check-jseval-lock and npm run lint:scripts. License inputs were generated from
this candidate's Gradle dependency report, npm license-checker and Cargo metadata;
no dependency or NOTICE update was needed.

Logs are task-local scratch evidence. Most live under the implementation
worktree's tmp directory; recovery logs are in the task's preserved-builds archive.
Runnable Java/TypeScript tests and browser harness steps are committed.

The first Java report-writing pass and accessibility sweep were interrupted by
an exhausted workspace disk. Task-owned disposable dependency/build output was
preserved on another volume, then both checks were rerun successfully. The first
license attempt lacked --no-parallel; the successful run uses the documented
license command. The initial a11y invocation used an unsupported --fixtures flag;
the gate supplies fixtures itself. None of these failed preparations is counted
as a passing run. Generated reports and local caches are not publication assets.

The browser fixture console still reports the previously recorded transition
errors and control self-checks; zero axe findings is not a clean-console claim.
The earlier owned live MCP and real-model smoke evidence remains in §V with its
original product provenance. The final async failure path is covered through the
real justsearch_answer consumer with failed futures, not a claimed live outage.
A second live stack was not taken from the concurrently active 919 task.
No new paid utility benchmark was run; this is bounded failure wording/metadata,
not a retrieval-quality or autonomous-agent retry benchmark claim.

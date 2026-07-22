# JustSearch winget manifest

A [winget-pkgs](https://github.com/microsoft/winget-pkgs) manifest set (schema 1.6.0) for
`PackageIdentifier: eliasjustus.JustSearch`. **This directory is a committed skeleton, not a
submission** — the three files below have `TODO-GA:` / `TODO-OWNER:` placeholders that can only be
filled in once a real GA release exists (`v0.2.x` or later, non-prerelease). See tempdoc 760 §Phase
2 item 2.

## Why this exists before a release

Listing JustSearch on winget is chartered independently of the code-signing decision: per
Microsoft's own guidance, an app listed in the winget repository installs **without Smart App
Control interference**, which partially mitigates the unsigned-installer trust problem in the
meantime (see [`docs/how-to/verify-your-download.md`](../../docs/how-to/verify-your-download.md)).
`InstallerType: nullsoft` also means winget auto-derives the silent-install switches from the NSIS
installer type — no custom switch flags to author here.

## Files

| File | winget manifest type | Purpose |
|---|---|---|
| `eliasjustus.JustSearch.yaml` | `version` | Points at the default locale + pins the version being described. |
| `eliasjustus.JustSearch.installer.yaml` | `installer` | Installer URL, SHA-256, type, scope, silent-install modes. |
| `eliasjustus.JustSearch.locale.en-US.yaml` | `defaultLocale` | Publisher, description, license, tags. |

## Placeholders to fill in at GA (owner + agent, after the tag is cut)

All four `TODO-GA:` fields are release-timing-dependent — they don't exist until a real tag is
pushed and its `SHA256SUMS` is published:

1. **`PackageVersion`** (all three files, must match exactly) — the released version, e.g. `0.2.0`.
2. **`InstallerUrl`** — replace `<VERSION>` with the real version in
   `https://github.com/eliasjustus/justsearch/releases/download/v<VERSION>/JustSearch_<VERSION>_x64-setup.exe`.
3. **`InstallerSha256`** — the installer's hash from that release's `SHA256SUMS`
   (`scripts/ci/build-release-assets.ps1`), **uppercased** (winget-pkgs convention; `SHA256SUMS`
   itself is lowercase).
4. **`ReleaseDate`** — the ISO date (`YYYY-MM-DD`) the GitHub Release was published, not the build
   date.

One field is an **owner decision**, not release-timing dependent, so it can be filled in any time
before submission:

- **`Publisher`** (`TODO-OWNER-PUBLISHER-NAME`) — the legal name winget-pkgs expects in this field
  (individual or business name, per how the owner wants to be listed). `PublisherUrl` stays
  `TODO-GA` regardless, since there is no public project website yet — a separate, external
  dependency this tempdoc explicitly does not solve.

## Validating locally

```powershell
winget validate --manifest packaging/winget/
```

Expected **before** the placeholders above are filled in: this will fail — `PackageVersion`,
`InstallerSha256`, and `ReleaseDate` placeholders don't conform to winget's schema (version
regex, 64-hex-char SHA-256, ISO date), and the `InstallerUrl` still contains a literal `<VERSION>`
token. That's the point of the skeleton: it's a scaffold to fill in, not a green manifest.

## Submitting (after a GA release exists)

1. Fill in the placeholders above from the real release's assets.
2. Re-run `winget validate --manifest packaging/winget/` locally — must be clean before opening a
   PR.
3. **Re-confirm winget-pkgs' current policy on unsigned NSIS installers against their
   [CONTRIBUTING guide](https://github.com/microsoft/winget-pkgs/blob/master/CONTRIBUTING.md)
   before submitting.** Tempdoc 760's Phase 1 audit found unsigned NSIS apps are listed in
   winget-pkgs empirically (no verbatim primary-source rule against it was found), but that is not
   the same as a durable policy guarantee — confirm against whatever CONTRIBUTING says *at
   submission time*, not against this note.
4. Submit via [`wingetcreate`](https://github.com/microsoft/winget-create) (recommended — it can
   also generate/update manifests from an installer URL directly) or a manual PR against
   `winget-pkgs`, placing the three files at
   `manifests/e/eliasjustus/JustSearch/<version>/` in that repo (winget-pkgs' own directory
   convention; this directory here stays flat).
   ```powershell
   wingetcreate submit packaging/winget/eliasjustus.JustSearch.yaml `
     packaging/winget/eliasjustus.JustSearch.installer.yaml `
     packaging/winget/eliasjustus.JustSearch.locale.en-US.yaml
   ```
   (or `wingetcreate update eliasjustus.JustSearch --version <version> --urls <installer-url>` to
   have it fetch and hash the installer itself, then review the diff before submitting.)
5. winget-pkgs CI runs its own validation + installs the package in a sandboxed VM
   (`silent`/`silentWithProgress` modes, both admin and non-admin) before a moderator merges the
   PR — this is the first real-world exercise of JustSearch's silent install/uninstall path (see
   tempdoc 760 Phase 2 item 1).

## See also

- [`docs/how-to/verify-your-download.md`](../../docs/how-to/verify-your-download.md) — the
  user-facing trust doc this manifest is one part of.
- [`docs/how-to/cut-a-release.md`](../../docs/how-to/cut-a-release.md) — where the version,
  installer asset, and `SHA256SUMS` these placeholders need actually come from.
- `docs/tempdocs/760-installer-distribution-readiness.md` — the chartering tempdoc, Findings +
  Phase 2 item 2.

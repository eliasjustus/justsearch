# m1: Owner-only actions (shippability funnel)

Each item below is something only the repo owner can do — the `m1-shippability` branch fixed
everything fixable in-repo. One line each, exact location.

- [ ] **Review and merge/push the `m1-shippability` branch** — it exists only locally in this checkout; nothing was pushed.
- [ ] **Verify the installer checksum against the real release asset** — download `JustSearch_0.1.0_x64-setup.exe` from <https://github.com/eliasjustus/justsearch/releases/tag/v0.1.0>, run `certutil -hashfile JustSearch_0.1.0_x64-setup.exe SHA256`, and confirm it equals the hash in `SHA256SUMS` / README (computed from the local build; byte size already matches the asset).
- [ ] **Upload `SHA256SUMS` to the v0.1.0 release** — repo → Releases → v0.1.0 → Edit → attach the file (after the verification above).
- [ ] **Promote a non-prerelease so `/releases/latest` resolves** — Releases → v0.1.0 → Edit → untick "Set as a pre-release" (or publish the next release without it); until then `https://github.com/eliasjustus/justsearch/releases/latest` does not point at v0.1.0 (it is marked Pre-release).
- [ ] **Set the repo homepage field** — repo front page → gear icon next to About → Website (currently empty; `homepageUrl: ""`).
- [ ] **Decide on Discussions** — Settings → General → Features → tick "Discussions" to enable it, then optionally point SUPPORT.md/CONTRIBUTING.md links back at it; otherwise keep the Issues routing this branch installed (Discussions is currently disabled and the old links 404'd).
- [ ] **Take the two hero screenshots** — see `docs/m1-screenshot-instructions.md`, then replace the README hero placeholder and delete both m1 doc files.
- [ ] **Resolve the two TODO comments in README** — the RAM row (conservative estimate, not measured) and the Windows 10 note (only Windows 11 verified) in the System requirements table.

Delete this file when the list is done.

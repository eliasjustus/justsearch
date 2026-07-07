---
title: "Public release trust evidence: make security, supply-chain, model, and installer claims externally verifiable instead of merely well-documented"
type: tempdocs
status: open
created: 2026-06-28
updated: 2026-06-28
category: security / release-engineering / supply-chain / public-trust
related:
  - 631-go-public-publish-machinery
  - 632-go-public-licensing-legal
  - 634-go-public-cutover-transition
  - 650-go-public-capability-descriptor-truthfulness
  - docs/reference/security/threat-model.md
  - SECURITY.md
---

> NOTE: Noncanonical working tempdoc. Verify against canonical docs and code before
> treating any claim as current truth.

# 659 - Public release trust evidence

## Purpose

The Glasswing/OpenSSF-style report is directionally useful but too broad to implement as one slice.
The actionable core is narrower: JustSearch should make its release, security, supply-chain, and model
trust claims externally verifiable. A local-first agent runtime that handles sensitive files needs
public evidence, not just internal discipline.

This tempdoc asks a next agent to design the evidence package: release checksums, signing/provenance,
SBOMs, model artifact hashes, release verification docs, workflow permission audit, and the boundaries
between existing `SECURITY.md`, canonical threat-model docs, and release engineering.

## Boundary

Do not frame this as "become Glasswing-ready" or "critical infrastructure" in public product language.
That may be a long-term aspiration, but the near-term tempdoc should stay concrete: what artifacts can
outsiders inspect to verify what was built, what it contains, which models are trusted, and how
security reports are handled.

Do not duplicate `go-public-publish-machinery` or `go-public-cutover-transition`; those own the
public-repo transition mechanics. This note owns the next evidence layer after public launch.

## Prior owners to read first

- `go-public-publish-machinery`, `go-public-licensing-legal`, and `go-public-cutover-transition`.
- `go-public-capability-descriptor-truthfulness` for public claim discipline.
- `SECURITY.md` and `docs/reference/security/threat-model.md` for existing disclosure and threat scope.
- Current GitHub workflows for what evidence CI already produces.
- `docs/reference/model-inventory.md` for model inventory and license/trust inputs.

## First questions

- Which release artifacts need checksums, signatures, provenance, or attestations?
- What SBOM scope is realistic for the app, installer, runtime services, and models?
- Should model hashes be release evidence only, runtime-enforced policy, or both?
- Which workflow permission and action-pinning changes matter before stable release?
- Where should release-verification and model-supply-chain docs live canonically?


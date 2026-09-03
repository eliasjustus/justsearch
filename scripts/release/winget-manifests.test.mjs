import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  checkManifestSet,
  parseAndValidateManifestSet,
  renderManifestSet,
  renderPrBody,
  validateMetadata,
  validateReleaseInput,
  writeManifestSet,
} from "./winget-manifests.mjs";

const root = path.resolve(import.meta.dirname, "../..");
const metadataPath = path.join(root, "packaging/winget/package.v1.json");
const releasePath = path.join(root, "packaging/winget/releases/v0.2.0.json");
const committedPath = path.join(
  root,
  "packaging/winget/manifests/e/eliasjustus/JustSearch/0.2.0",
);

function load(filePath) {
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

test("the v0.2.0 release input regenerates the committed YAML-only set", () => {
  const metadata = load(metadataPath);
  const release = load(releasePath);
  const files = renderManifestSet(metadata, release);
  assert.deepEqual([...files.keys()].sort(), [
    "eliasjustus.JustSearch.installer.yaml",
    "eliasjustus.JustSearch.locale.en-US.yaml",
    "eliasjustus.JustSearch.yaml",
  ]);
  checkManifestSet(committedPath, files, metadata, release);
  assert.ok(
    fs.readdirSync(committedPath).every((name) => name.endsWith(".yaml")),
    "generated directory must contain YAML only",
  );
});

test("generation is deterministic and check detects changed bytes", (context) => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), "justsearch-winget-"));
  context.after(() => fs.rmSync(temporaryRoot, { recursive: true, force: true }));
  const metadata = load(metadataPath);
  const release = load(releasePath);
  const files = renderManifestSet(metadata, release);
  writeManifestSet(temporaryRoot, files);
  checkManifestSet(temporaryRoot, files, metadata, release);

  const versionManifest = path.join(temporaryRoot, "eliasjustus.JustSearch.yaml");
  fs.appendFileSync(versionManifest, "# drift\n", "utf8");
  assert.throws(
    () => checkManifestSet(temporaryRoot, files, metadata, release),
    /manifests are stale/,
  );
});

test("checker parses YAML and rejects cross-file semantic disagreement", (context) => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), "justsearch-winget-"));
  context.after(() => fs.rmSync(temporaryRoot, { recursive: true, force: true }));
  const metadata = load(metadataPath);
  const release = load(releasePath);
  const files = renderManifestSet(metadata, release);
  writeManifestSet(temporaryRoot, files);

  const localeManifest = path.join(
    temporaryRoot,
    "eliasjustus.JustSearch.locale.en-US.yaml",
  );
  const original = fs.readFileSync(localeManifest, "utf8");
  fs.writeFileSync(
    localeManifest,
    original.replace('PackageVersion: "0.2.0"', 'PackageVersion: "0.2.1"'),
    "utf8",
  );
  assert.throws(
    () => checkManifestSet(temporaryRoot, files, metadata, release),
    /cross-file PackageVersion mismatch/,
  );

  fs.writeFileSync(localeManifest, original.replace("Tags:", "Tags: ["), "utf8");
  assert.throws(
    () => parseAndValidateManifestSet(temporaryRoot, metadata, release),
    /could not parse .* as YAML/,
  );
});

test("release identity is closed over tag, URL, filename, hash, and publication state", () => {
  const metadata = validateMetadata(load(metadataPath));
  const release = load(releasePath);
  const mutations = [
    ["tag", "v0.2.1"],
    ["version", "0.2"],
    ["publishedAt", "2026-08-13"],
    ["draft", true],
  ];
  for (const [field, value] of mutations) {
    assert.throws(() => validateReleaseInput({ ...release, [field]: value }, metadata));
  }
  assert.throws(() =>
    validateReleaseInput(
      { ...release, installer: { ...release.installer, name: "other.exe" } },
      metadata,
    ),
  );
  assert.throws(
    () =>
      validateReleaseInput(
        {
          ...release,
          installer: { ...release.installer, githubSha256: "f".repeat(64) },
        },
        metadata,
      ),
    /does not agree/,
  );
  assert.throws(() =>
    validateReleaseInput(
      { ...release, installer: { ...release.installer, url: "https://example.test/a.exe" } },
      metadata,
    ),
  );
  assert.throws(() =>
    validateReleaseInput(
      { ...release, installer: { ...release.installer, sha256: "0".repeat(63) } },
      metadata,
    ),
  );
});

test("PR body is an evidence handoff, not an upstream mutation", () => {
  const body = renderPrBody(
    load(metadataPath),
    load(releasePath),
    "dist/winget/manifests/e/eliasjustus/JustSearch/0.2.0",
  );
  assert.match(body, /checksum asset ID: `512958191`/);
  assert.match(body, /does not open or submit an upstream pull request/);
  assert.doesNotMatch(body, /gh pr create|wingetcreate submit/);
});

test("generator refuses to mix non-manifest files into its output", (context) => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), "justsearch-winget-"));
  context.after(() => fs.rmSync(temporaryRoot, { recursive: true, force: true }));
  fs.writeFileSync(path.join(temporaryRoot, "README.md"), "foreign", "utf8");
  assert.throws(
    () => writeManifestSet(temporaryRoot, renderManifestSet(load(metadataPath), load(releasePath))),
    /unexpected entries: README\.md/,
  );
});

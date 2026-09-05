#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import yaml from "js-yaml";

const SHA256_RE = /^[0-9a-f]{64}$/i;
const VERSION_RE = /^\d+\.\d+\.\d+$/;
const DATE_TIME_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/;

function fail(message) {
  throw new Error(message);
}

function requireObject(value, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    fail(`${label} must be an object`);
  }
  return value;
}

function requireString(value, label) {
  if (typeof value !== "string" || value.trim() === "") {
    fail(`${label} must be a non-empty string`);
  }
  return value;
}

function requirePositiveInteger(value, label) {
  if (!Number.isSafeInteger(value) || value <= 0) {
    fail(`${label} must be a positive integer`);
  }
  return value;
}

function requireHttpsUrl(value, label) {
  requireString(value, label);
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    fail(`${label} must be a valid URL`);
  }
  if (parsed.protocol !== "https:") {
    fail(`${label} must use HTTPS`);
  }
  return value;
}

export function validateMetadata(raw) {
  const metadata = requireObject(raw, "metadata");
  if (metadata.schemaVersion !== 1) fail("metadata.schemaVersion must be 1");
  if (metadata.manifestVersion !== "1.12.0") {
    fail("metadata.manifestVersion must be 1.12.0");
  }

  for (const field of [
    "packageIdentifier",
    "defaultLocale",
    "publisher",
    "packageName",
    "license",
    "licensePath",
    "shortDescription",
    "description",
    "moniker",
  ]) {
    requireString(metadata[field], `metadata.${field}`);
  }
  for (const field of ["publisherUrl", "publisherSupportUrl", "packageUrl"]) {
    requireHttpsUrl(metadata[field], `metadata.${field}`);
  }
  if (!/^[A-Za-z0-9]+(?:\.[A-Za-z0-9]+)+$/.test(metadata.packageIdentifier)) {
    fail("metadata.packageIdentifier is not a valid WinGet package identifier");
  }
  if (!Array.isArray(metadata.tags) || metadata.tags.length === 0) {
    fail("metadata.tags must be a non-empty array");
  }
  metadata.tags.forEach((tag, index) => requireString(tag, `metadata.tags[${index}]`));

  const installer = requireObject(metadata.installer, "metadata.installer");
  for (const field of [
    "architecture",
    "locale",
    "type",
    "scope",
    "platform",
    "upgradeBehavior",
    "assetNameTemplate",
  ]) {
    requireString(installer[field], `metadata.installer.${field}`);
  }
  if (!installer.assetNameTemplate.includes("{version}")) {
    fail("metadata.installer.assetNameTemplate must contain {version}");
  }
  if (!Array.isArray(installer.installModes) || installer.installModes.length === 0) {
    fail("metadata.installer.installModes must be a non-empty array");
  }
  installer.installModes.forEach((mode, index) =>
    requireString(mode, `metadata.installer.installModes[${index}]`),
  );
  return metadata;
}

export function validateReleaseInput(raw, metadata) {
  const release = requireObject(raw, "release input");
  if (release.schemaVersion !== 1) fail("release.schemaVersion must be 1");
  requireString(release.repository, "release.repository");
  requirePositiveInteger(release.releaseId, "release.releaseId");
  requireString(release.tag, "release.tag");
  requireString(release.version, "release.version");
  requireString(release.publishedAt, "release.publishedAt");
  if (!VERSION_RE.test(release.version)) {
    fail("release.version must be a stable three-part version without a leading v");
  }
  if (release.tag !== `v${release.version}`) {
    fail(`release.tag must be v${release.version}`);
  }
  if (release.draft !== false || release.prerelease !== false) {
    fail("WinGet manifests may only be generated for a published, stable release");
  }
  if (!DATE_TIME_RE.test(release.publishedAt) || Number.isNaN(Date.parse(release.publishedAt))) {
    fail("release.publishedAt must be a UTC ISO-8601 timestamp");
  }

  const packageUrl = new URL(metadata.packageUrl);
  const expectedRepository = packageUrl.pathname.replace(/^\//, "").replace(/\/$/, "");
  if (release.repository !== expectedRepository) {
    fail(`release.repository must match metadata.packageUrl (${expectedRepository})`);
  }

  const checksumManifest = requireObject(release.checksumManifest, "release.checksumManifest");
  requirePositiveInteger(checksumManifest.assetId, "release.checksumManifest.assetId");
  requirePositiveInteger(checksumManifest.sizeBytes, "release.checksumManifest.sizeBytes");
  if (checksumManifest.name !== "SHA256SUMS") {
    fail("release.checksumManifest.name must be SHA256SUMS");
  }
  requireHttpsUrl(checksumManifest.url, "release.checksumManifest.url");
  requireString(checksumManifest.sha256, "release.checksumManifest.sha256");
  if (!SHA256_RE.test(checksumManifest.sha256)) {
    fail("release.checksumManifest.sha256 must contain exactly 64 hexadecimal characters");
  }
  const expectedChecksumUrl = `${metadata.packageUrl}/releases/download/${release.tag}/SHA256SUMS`;
  if (checksumManifest.url !== expectedChecksumUrl) {
    fail(`release.checksumManifest.url must be ${expectedChecksumUrl}`);
  }

  const installer = requireObject(release.installer, "release.installer");
  requirePositiveInteger(installer.assetId, "release.installer.assetId");
  requirePositiveInteger(installer.sizeBytes, "release.installer.sizeBytes");
  requireString(installer.name, "release.installer.name");
  requireHttpsUrl(installer.url, "release.installer.url");
  requireString(installer.sha256, "release.installer.sha256");
  requireString(installer.githubSha256, "release.installer.githubSha256");
  if (!SHA256_RE.test(installer.sha256)) {
    fail("release.installer.sha256 must contain exactly 64 hexadecimal characters");
  }
  if (!SHA256_RE.test(installer.githubSha256)) {
    fail("release.installer.githubSha256 must contain exactly 64 hexadecimal characters");
  }
  if (installer.sha256.toLowerCase() !== installer.githubSha256.toLowerCase()) {
    fail("release installer SHA-256 does not agree between SHA256SUMS and the GitHub asset digest");
  }

  const expectedName = metadata.installer.assetNameTemplate.replace(
    "{version}",
    release.version,
  );
  if (installer.name !== expectedName) {
    fail(`release.installer.name must be ${expectedName}`);
  }
  const expectedUrl = `${metadata.packageUrl}/releases/download/${release.tag}/${expectedName}`;
  if (installer.url !== expectedUrl) {
    fail(`release.installer.url must be ${expectedUrl}`);
  }
  return release;
}

function yamlString(value) {
  return JSON.stringify(value);
}

function yamlList(items, indent = "") {
  return items.map((item) => `${indent}- ${yamlString(item)}`).join("\n");
}

export function renderManifestSet(metadataRaw, releaseRaw) {
  const metadata = validateMetadata(metadataRaw);
  const release = validateReleaseInput(releaseRaw, metadata);
  const id = metadata.packageIdentifier;
  const version = release.version;
  const manifestVersion = metadata.manifestVersion;
  const releaseDate = release.publishedAt.slice(0, 10);
  const licenseUrl = `${metadata.packageUrl}/blob/${release.tag}/${metadata.licensePath}`;
  const releaseNotesUrl = `${metadata.packageUrl}/releases/tag/${release.tag}`;

  const files = new Map();
  files.set(
    `${id}.yaml`,
    [
      `# yaml-language-server: $schema=https://aka.ms/winget-manifest.version.${manifestVersion}.schema.json`,
      `PackageIdentifier: ${yamlString(id)}`,
      `PackageVersion: ${yamlString(version)}`,
      `DefaultLocale: ${yamlString(metadata.defaultLocale)}`,
      "ManifestType: version",
      `ManifestVersion: ${yamlString(manifestVersion)}`,
      "",
    ].join("\n"),
  );
  files.set(
    `${id}.installer.yaml`,
    [
      `# yaml-language-server: $schema=https://aka.ms/winget-manifest.installer.${manifestVersion}.schema.json`,
      `PackageIdentifier: ${yamlString(id)}`,
      `PackageVersion: ${yamlString(version)}`,
      `InstallerLocale: ${yamlString(metadata.installer.locale)}`,
      "Platform:",
      yamlList([metadata.installer.platform], "  "),
      `InstallerType: ${metadata.installer.type}`,
      `Scope: ${metadata.installer.scope}`,
      "InstallModes:",
      yamlList(metadata.installer.installModes, "  "),
      `UpgradeBehavior: ${metadata.installer.upgradeBehavior}`,
      `ReleaseDate: ${yamlString(releaseDate)}`,
      "Installers:",
      `  - Architecture: ${metadata.installer.architecture}`,
      `    InstallerUrl: ${yamlString(release.installer.url)}`,
      `    InstallerSha256: ${release.installer.sha256.toUpperCase()}`,
      "ManifestType: installer",
      `ManifestVersion: ${yamlString(manifestVersion)}`,
      "",
    ].join("\n"),
  );
  files.set(
    `${id}.locale.${metadata.defaultLocale}.yaml`,
    [
      `# yaml-language-server: $schema=https://aka.ms/winget-manifest.defaultLocale.${manifestVersion}.schema.json`,
      `PackageIdentifier: ${yamlString(id)}`,
      `PackageVersion: ${yamlString(version)}`,
      `PackageLocale: ${yamlString(metadata.defaultLocale)}`,
      `Publisher: ${yamlString(metadata.publisher)}`,
      `PublisherUrl: ${yamlString(metadata.publisherUrl)}`,
      `PublisherSupportUrl: ${yamlString(metadata.publisherSupportUrl)}`,
      `PackageName: ${yamlString(metadata.packageName)}`,
      `PackageUrl: ${yamlString(metadata.packageUrl)}`,
      `License: ${yamlString(metadata.license)}`,
      `LicenseUrl: ${yamlString(licenseUrl)}`,
      `ShortDescription: ${yamlString(metadata.shortDescription)}`,
      `Description: ${yamlString(metadata.description)}`,
      `Moniker: ${yamlString(metadata.moniker)}`,
      "Tags:",
      yamlList(metadata.tags, "  "),
      `ReleaseNotesUrl: ${yamlString(releaseNotesUrl)}`,
      "ManifestType: defaultLocale",
      `ManifestVersion: ${yamlString(manifestVersion)}`,
      "",
    ].join("\n"),
  );
  return files;
}

export function renderPrBody(metadataRaw, releaseRaw, manifestPath) {
  const metadata = validateMetadata(metadataRaw);
  const release = validateReleaseInput(releaseRaw, metadata);
  return [
    `## ${metadata.packageName} ${release.version}`,
    "",
    `- Package: \`${metadata.packageIdentifier}\``,
    `- Release: [${release.tag}](${metadata.packageUrl}/releases/tag/${release.tag})`,
    `- Installer: [${release.installer.name}](${release.installer.url})`,
    `- Installer SHA-256: \`${release.installer.sha256.toUpperCase()}\``,
    `- Checksum manifest: [SHA256SUMS](${release.checksumManifest.url})`,
    `- Published: \`${release.publishedAt}\``,
    `- GitHub release ID: \`${release.releaseId}\`; installer asset ID: \`${release.installer.assetId}\`; checksum asset ID: \`${release.checksumManifest.assetId}\``,
    "",
    "The manifests were generated from the repository's package metadata authority and the",
    "published GitHub Release API record, then checked with `winget validate`. This workflow",
    "does not open or submit an upstream pull request.",
    "",
    `Manifest source: \`${manifestPath.replaceAll("\\", "/")}\``,
    "",
  ].join("\n");
}

export function writeManifestSet(outputDir, files) {
  fs.mkdirSync(outputDir, { recursive: true });
  const expected = new Set(files.keys());
  const unexpected = fs
    .readdirSync(outputDir, { withFileTypes: true })
    .filter((entry) => !entry.isFile() || !expected.has(entry.name))
    .map((entry) => entry.name);
  if (unexpected.length > 0) {
    fail(`output directory contains unexpected entries: ${unexpected.join(", ")}`);
  }
  for (const [name, content] of files) {
    fs.writeFileSync(path.join(outputDir, name), content, "utf8");
  }
}

function requireParsedObject(value, filename) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    fail(`${filename} must contain one YAML mapping`);
  }
  return value;
}

export function parseAndValidateManifestSet(outputDir, metadataRaw, releaseRaw) {
  const metadata = validateMetadata(metadataRaw);
  const release = validateReleaseInput(releaseRaw, metadata);
  const parsedByType = new Map();
  for (const filename of fs.readdirSync(outputDir)) {
    const filePath = path.join(outputDir, filename);
    let document;
    try {
      document = requireParsedObject(yaml.load(fs.readFileSync(filePath, "utf8")), filename);
    } catch (error) {
      fail(`could not parse ${filename} as YAML: ${error.message}`);
    }
    requireString(document.ManifestType, `${filename}.ManifestType`);
    if (parsedByType.has(document.ManifestType)) {
      fail(`duplicate ManifestType ${document.ManifestType}`);
    }
    parsedByType.set(document.ManifestType, { filename, document });
  }

  const requiredTypes = ["version", "installer", "defaultLocale"];
  for (const type of requiredTypes) {
    if (!parsedByType.has(type)) fail(`manifest set is missing ManifestType ${type}`);
  }
  if (parsedByType.size !== requiredTypes.length) {
    fail(`manifest set contains unexpected ManifestType values: ${[...parsedByType.keys()].join(", ")}`);
  }
  const documents = requiredTypes.map((type) => parsedByType.get(type).document);
  const identifiers = new Set(documents.map((document) => document.PackageIdentifier));
  if (identifiers.size !== 1) fail("cross-file PackageIdentifier mismatch");
  const versions = new Set(documents.map((document) => document.PackageVersion));
  if (versions.size !== 1) fail("cross-file PackageVersion mismatch");
  const schemaVersions = new Set(documents.map((document) => document.ManifestVersion));
  if (schemaVersions.size !== 1) fail("cross-file ManifestVersion mismatch");
  if ([...identifiers][0] !== metadata.packageIdentifier) {
    fail(`PackageIdentifier must be ${metadata.packageIdentifier}`);
  }
  if ([...versions][0] !== release.version) fail(`PackageVersion must be ${release.version}`);
  if ([...schemaVersions][0] !== metadata.manifestVersion) {
    fail(`ManifestVersion must be ${metadata.manifestVersion}`);
  }

  const versionManifest = parsedByType.get("version").document;
  const installerManifest = parsedByType.get("installer").document;
  const localeManifest = parsedByType.get("defaultLocale").document;
  if (
    versionManifest.DefaultLocale !== localeManifest.PackageLocale ||
    versionManifest.DefaultLocale !== metadata.defaultLocale
  ) {
    fail("cross-file default locale mismatch");
  }
  if (installerManifest.InstallerLocale !== metadata.installer.locale) {
    fail(`InstallerLocale must be ${metadata.installer.locale}`);
  }
  if (installerManifest.ReleaseDate !== release.publishedAt.slice(0, 10)) {
    fail(`ReleaseDate must be ${release.publishedAt.slice(0, 10)}`);
  }
  if (!Array.isArray(installerManifest.Installers) || installerManifest.Installers.length !== 1) {
    fail("installer manifest must contain exactly one installer");
  }
  const installer = requireParsedObject(installerManifest.Installers[0], "Installers[0]");
  if (installer.Architecture !== metadata.installer.architecture) {
    fail(`installer Architecture must be ${metadata.installer.architecture}`);
  }
  if (installer.InstallerUrl !== release.installer.url) {
    fail("installer URL does not match the authenticated release input");
  }
  if (installer.InstallerSha256 !== release.installer.sha256.toUpperCase()) {
    fail("installer SHA-256 does not match the authenticated release input");
  }
  return parsedByType;
}

export function checkManifestSet(outputDir, files, metadataRaw, releaseRaw) {
  if (!fs.existsSync(outputDir)) fail(`manifest directory does not exist: ${outputDir}`);
  const actualNames = fs.readdirSync(outputDir).sort();
  const expectedNames = [...files.keys()].sort();
  if (JSON.stringify(actualNames) !== JSON.stringify(expectedNames)) {
    fail(
      `manifest directory entries differ: expected ${expectedNames.join(", ")}; got ${actualNames.join(", ")}`,
    );
  }
  parseAndValidateManifestSet(outputDir, metadataRaw, releaseRaw);
  const drift = [];
  for (const [name, expected] of files) {
    const actual = fs.readFileSync(path.join(outputDir, name), "utf8").replaceAll("\r\n", "\n");
    if (actual !== expected) drift.push(name);
  }
  if (drift.length > 0) fail(`generated WinGet manifests are stale: ${drift.join(", ")}`);
}

function readJson(filePath, label) {
  let parsed;
  try {
    parsed = JSON.parse(fs.readFileSync(filePath, "utf8"));
  } catch (error) {
    fail(`could not read ${label} ${filePath}: ${error.message}`);
  }
  return parsed;
}

function parseArgs(argv) {
  const [command, ...rest] = argv;
  if (!command || !["generate", "check"].includes(command)) {
    fail("usage: winget-manifests.mjs <generate|check> --metadata <json> --release <json> --output <dir> [--pr-body <md>]");
  }
  const options = {};
  for (let index = 0; index < rest.length; index += 2) {
    const flag = rest[index];
    const value = rest[index + 1];
    if (!flag?.startsWith("--") || value === undefined) fail(`invalid argument: ${flag ?? "<missing>"}`);
    options[flag.slice(2)] = value;
  }
  for (const required of ["metadata", "release", "output"]) {
    if (!options[required]) fail(`--${required} is required`);
  }
  return { command, options };
}

export function runCli(argv) {
  const { command, options } = parseArgs(argv);
  const metadata = readJson(options.metadata, "metadata");
  const release = readJson(options.release, "release input");
  const files = renderManifestSet(metadata, release);
  if (command === "generate") {
    writeManifestSet(options.output, files);
    if (options["pr-body"]) {
      fs.mkdirSync(path.dirname(options["pr-body"]), { recursive: true });
      fs.writeFileSync(
        options["pr-body"],
        renderPrBody(metadata, release, options.output),
        "utf8",
      );
    }
    console.log(`generated ${files.size} WinGet manifests in ${options.output}`);
  } else {
    if (options["pr-body"]) fail("--pr-body is only supported by generate");
    checkManifestSet(options.output, files, metadata, release);
    console.log(`verified ${files.size} deterministic WinGet manifests in ${options.output}`);
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  try {
    runCli(process.argv.slice(2));
  } catch (error) {
    console.error(`WinGet manifest error: ${error.message}`);
    process.exitCode = 1;
  }
}

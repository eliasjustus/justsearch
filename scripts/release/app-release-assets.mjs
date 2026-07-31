import {
  createHash,
  createPrivateKey,
  createPublicKey,
  sign,
  verify,
} from 'node:crypto';
import {
  mkdir,
  readFile,
  writeFile,
} from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const MINISIGN_ED25519_SPKI_PREFIX = Buffer.from(
  '302a300506032b6570032100',
  'hex',
);
const TRUSTED_COMMENT_PREFIX = 'trusted comment: ';

export function canonicalJson(value) {
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(',')}]`;
  }
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
      .join(',')}}`;
  }
  return JSON.stringify(value);
}

export async function buildReleaseAssets(options) {
  const {
    installerPath,
    artifactSignaturePath,
    metadataPrivateKeyPath,
    compatibilityRegisterPath,
    outDir,
    version,
    sequence,
    installerUrl,
    artifactKeyId,
    artifactPublicKey,
    publishedAt = new Date().toISOString(),
    notes = '',
  } = options;
  requireNonBlank('version', version);
  requireNonBlank('installerUrl', installerUrl);
  requireNonBlank('artifactKeyId', artifactKeyId);
  requireNonBlank('artifactPublicKey', artifactPublicKey);
  if (!Number.isSafeInteger(sequence) || sequence <= 0) {
    throw new Error('sequence must be a positive safe integer');
  }

  const [installer, artifactSignature, privateKeyPem, registerRaw] = await Promise.all([
    readFile(installerPath),
    readFile(artifactSignaturePath, 'utf8'),
    readFile(metadataPrivateKeyPath, 'utf8'),
    readFile(compatibilityRegisterPath, 'utf8'),
  ]);
  const register = JSON.parse(registerRaw);
  verifyTauriArtifactSignature(
    installer,
    artifactSignature.trim(),
    artifactPublicKey.trim(),
  );
  if ((register.knownCompatibilityGaps ?? []).length > 0) {
    throw new Error(
      `compatibility register is not release-ready: ${register.knownCompatibilityGaps.join(', ')}`,
    );
  }
  const compatibility = (register.durableStores ?? []).map((store) => {
    if (store.status !== 'READY') {
      throw new Error(`durable store ${store.id} is not READY`);
    }
    return {
      ownerId: store.id,
      currentVersion: store.currentVersion,
      readableSourceVersions: [
        ...(store.readableLegacyVersions ?? []),
        store.currentVersion,
      ].sort((a, b) => a - b),
      reconciliation: store.reconciliation,
    };
  });

  const descriptor = {
    schemaVersion: 1,
    sequence,
    version,
    channel: 'stable',
    target: 'windows-x86_64',
    publishedAt,
    artifact: {
      url: installerUrl,
      sha256: createHash('sha256').update(installer).digest('hex'),
      signature: artifactSignature.trim(),
      keyId: artifactKeyId,
      publicKey: artifactPublicKey.trim(),
    },
    compatibility,
  };
  const descriptorBytes = Buffer.from(`${canonicalJson(descriptor)}\n`);
  const metadataSignature = sign(
    null,
    descriptorBytes,
    createPrivateKey(privateKeyPem),
  ).toString('base64');
  const latest = {
    version,
    notes,
    pub_date: publishedAt,
    platforms: {
      'windows-x86_64': {
        signature: artifactSignature.trim(),
        url: installerUrl,
      },
    },
  };

  await mkdir(outDir, { recursive: true });
  await Promise.all([
    writeFile(path.join(outDir, 'release.v1.json'), descriptorBytes),
    writeFile(path.join(outDir, 'release.v1.json.sig'), `${metadataSignature}\n`),
    writeFile(path.join(outDir, 'latest.json'), `${JSON.stringify(latest, null, 2)}\n`),
  ]);
  return { descriptor, latest, metadataSignature };
}

export async function verifyReleaseAssets(options) {
  const {
    installerPath,
    artifactSignaturePath,
    metadataPublicKeyPath,
    releaseDir,
  } = options;
  const [installer, artifactSignatureRaw, publicKeyPem, descriptorBytes, signatureRaw, latestRaw] =
    await Promise.all([
      readFile(installerPath),
      readFile(artifactSignaturePath, 'utf8'),
      readFile(metadataPublicKeyPath, 'utf8'),
      readFile(path.join(releaseDir, 'release.v1.json')),
      readFile(path.join(releaseDir, 'release.v1.json.sig'), 'utf8'),
      readFile(path.join(releaseDir, 'latest.json'), 'utf8'),
    ]);
  const descriptor = JSON.parse(descriptorBytes);
  const latest = JSON.parse(latestRaw);
  if (descriptor.schemaVersion !== 1 || descriptor.channel !== 'stable') {
    throw new Error('release descriptor schema/channel mismatch');
  }
  if (!Number.isSafeInteger(descriptor.sequence) || descriptor.sequence <= 0) {
    throw new Error('release descriptor sequence is invalid');
  }
  if (
    !verify(
      null,
      descriptorBytes,
      createPublicKey(publicKeyPem),
      Buffer.from(signatureRaw.trim(), 'base64'),
    )
  ) {
    throw new Error('release metadata signature verification failed');
  }
  const installerHash = createHash('sha256').update(installer).digest('hex');
  if (descriptor.artifact?.sha256 !== installerHash) {
    throw new Error('installer digest does not match release descriptor');
  }
  const artifactSignature = artifactSignatureRaw.trim();
  verifyTauriArtifactSignature(
    installer,
    artifactSignature,
    descriptor.artifact?.publicKey,
  );
  const latestWindows = latest.platforms?.['windows-x86_64'];
  if (
    descriptor.version !== latest.version
    || descriptor.artifact.url !== latestWindows?.url
    || descriptor.artifact.signature !== artifactSignature
    || latestWindows.signature !== artifactSignature
  ) {
    throw new Error('latest.json, descriptor, and artifact signature are not a closed set');
  }
  const owners = new Set();
  for (const owner of descriptor.compatibility ?? []) {
    if (!owner.ownerId || owners.has(owner.ownerId)) {
      throw new Error(`duplicate or missing compatibility owner: ${owner.ownerId}`);
    }
    owners.add(owner.ownerId);
    if (!owner.readableSourceVersions?.includes(owner.currentVersion)) {
      throw new Error(`compatibility owner ${owner.ownerId} cannot read its current version`);
    }
  }
  return { descriptor, latest };
}

/**
 * Mirror Tauri updater 2.x verification: both inputs are base64 wrappers around
 * Minisign text files, and legacy Ed plus prehashed ED signatures are accepted.
 */
export function verifyTauriArtifactSignature(
  artifactBytes,
  encodedSignature,
  encodedPublicKey,
) {
  requireNonBlank('artifactSignature', encodedSignature);
  requireNonBlank('artifactPublicKey', encodedPublicKey);

  const publicKeyText = decodeUtf8Base64('artifact public key', encodedPublicKey);
  const signatureText = decodeUtf8Base64('artifact signature', encodedSignature);
  const publicLines = publicKeyText.trimEnd().split(/\r?\n/);
  const signatureLines = signatureText.trimEnd().split(/\r?\n/);
  if (publicLines.length !== 2 || signatureLines.length !== 4) {
    throw new Error('Tauri artifact key or signature has invalid Minisign structure');
  }

  const publicPacket = decodePacket('Minisign public key', publicLines[1], 42);
  const signaturePacket = decodePacket('Minisign signature', signatureLines[1], 74);
  const globalSignature = decodePacket(
    'Minisign global signature',
    signatureLines[3],
    64,
  );
  const publicAlgorithm = publicPacket.subarray(0, 2).toString('ascii');
  const signatureAlgorithm = signaturePacket.subarray(0, 2).toString('ascii');
  if (
    !['Ed', 'ED'].includes(publicAlgorithm)
    || !['Ed', 'ED'].includes(signatureAlgorithm)
  ) {
    throw new Error('Tauri artifact signature uses an unsupported Minisign algorithm');
  }
  if (!publicPacket.subarray(2, 10).equals(signaturePacket.subarray(2, 10))) {
    throw new Error('Tauri artifact signature key id does not match its public key');
  }
  if (!signatureLines[2].startsWith(TRUSTED_COMMENT_PREFIX)) {
    throw new Error('Tauri artifact signature is missing its trusted comment');
  }

  const publicKey = createPublicKey({
    key: Buffer.concat([
      MINISIGN_ED25519_SPKI_PREFIX,
      publicPacket.subarray(10, 42),
    ]),
    format: 'der',
    type: 'spki',
  });
  const message =
    signatureAlgorithm === 'ED'
      ? createHash('blake2b512').update(artifactBytes).digest()
      : artifactBytes;
  const rawSignature = signaturePacket.subarray(10, 74);
  if (!verify(null, message, publicKey, rawSignature)) {
    throw new Error('Tauri artifact signature verification failed');
  }
  const globalMessage = Buffer.concat([
    rawSignature,
    Buffer.from(signatureLines[2].slice(TRUSTED_COMMENT_PREFIX.length)),
  ]);
  if (!verify(null, globalMessage, publicKey, globalSignature)) {
    throw new Error('Tauri artifact trusted-comment signature verification failed');
  }
}

function decodeUtf8Base64(name, value) {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(
      Buffer.from(value, 'base64'),
    );
  } catch (error) {
    throw new Error(`${name} is not base64-encoded UTF-8: ${error.message}`);
  }
}

function decodePacket(name, value, expectedLength) {
  const packet = Buffer.from(value, 'base64');
  if (packet.length !== expectedLength) {
    throw new Error(`${name} has invalid length`);
  }
  return packet;
}

function requireNonBlank(name, value) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`${name} must be non-blank`);
  }
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith('--')) throw new Error(`unexpected argument: ${arg}`);
    const key = arg.slice(2);
    const value = argv[i + 1];
    if (value == null || value.startsWith('--')) throw new Error(`missing value for --${key}`);
    out[key] = value;
    i += 1;
  }
  return out;
}

async function main() {
  const [command, ...rest] = process.argv.slice(2);
  const args = parseArgs(rest);
  if (command === 'build') {
    await buildReleaseAssets({
      installerPath: args.installer,
      artifactSignaturePath: args['artifact-signature'],
      metadataPrivateKeyPath: args['metadata-private-key'],
      compatibilityRegisterPath: args.compatibility,
      outDir: args['out-dir'],
      version: args.version,
      sequence: Number(args.sequence),
      installerUrl: args.url,
      artifactKeyId: args['artifact-key-id'],
      artifactPublicKey: args['artifact-public-key'],
      publishedAt: args['published-at'],
      notes: args.notes ?? '',
    });
  } else if (command === 'verify') {
    await verifyReleaseAssets({
      installerPath: args.installer,
      artifactSignaturePath: args['artifact-signature'],
      metadataPublicKeyPath: args['metadata-public-key'],
      releaseDir: args['release-dir'],
    });
  } else {
    throw new Error('usage: app-release-assets.mjs <build|verify> [options]');
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  main().catch((error) => {
    console.error(`app-release-assets: ${error.message}`);
    process.exitCode = 1;
  });
}

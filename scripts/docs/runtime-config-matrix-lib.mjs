import fs from "node:fs";
import path from "node:path";

function repoRootFromCwd() {
  const cwd = process.cwd();
  const markers = ["settings.gradle.kts", "build.gradle.kts", ".git"];
  for (let dir = cwd; ; dir = path.dirname(dir)) {
    if (markers.some((m) => fs.existsSync(path.join(dir, m)))) {
      return dir;
    }
    const parent = path.dirname(dir);
    if (parent === dir) {
      break;
    }
  }
  return cwd;
}

/**
 * Parses EnvRegistry.java for operator-facing (sysprop, envVar) pairs.
 * Handles both 2-arg and 3-arg constructors.
 */
export function parseEnvRegistry(envRegistryPath) {
  const text = fs.readFileSync(envRegistryPath, "utf8");
  const bySysprop = new Map();
  const byEnvVar = new Map();
  const entries = [];

  const constantPattern =
    /([A-Z0-9_]+)\s*\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*(?:,\s*"[^"]*")?\s*\)\s*[,;]/g;
  for (const match of text.matchAll(constantPattern)) {
    const constant = match[1];
    const sysprop = match[2];
    const envVar = match[3];
    entries.push({ constant, sysprop, envVar });
    bySysprop.set(sysprop, constant);
    byEnvVar.set(envVar, constant);
  }

  return { entries, bySysprop, byEnvVar };
}

/**
 * Parses ConfigKey.java for YAML-only config keys.
 */
export function parseConfigKeys(configKeyPath) {
  if (!fs.existsSync(configKeyPath)) {
    return { entries: [] };
  }
  const text = fs.readFileSync(configKeyPath, "utf8");
  const entries = [];

  const pattern = /([A-Z0-9_]+)\s*\(\s*"([^"]+)"\s*\)\s*[,;]/g;
  for (const match of text.matchAll(pattern)) {
    entries.push({ constant: match[1], configKey: match[2] });
  }
  return { entries };
}

/**
 * Parses ResolvedConfigBuilder.java for putYaml*() calls to extract YAML key mappings.
 * Returns a set of YAML config keys that have YAML-source contributions.
 */
export function parseYamlContributions(builderPath) {
  const text = fs.readFileSync(builderPath, "utf8");
  const yamlKeys = new Set();

  // putYaml("configKey", root, "yamlPath") — first arg is the config key
  const putYamlPattern =
    /putYaml(?:Int|Long|Boolean|Double|FromNode|FromNodeLower)?\(\s*"([^"]+)"\s*,/g;
  for (const match of text.matchAll(putYamlPattern)) {
    yamlKeys.add(match[1]);
  }

  // putYaml(EnvRegistry.SOMETHING.sysProp(), root, "yamlPath") — uses EnvRegistry accessor
  const putYamlEnvPattern =
    /putYaml\(\s*(?:io\.justsearch\.configuration\.)?EnvRegistry\.([A-Z0-9_]+)\.sysProp\(\)\s*,/g;
  for (const match of text.matchAll(putYamlEnvPattern)) {
    yamlKeys.add(`EnvRegistry.${match[1]}`); // marker, resolved during merge
  }

  return { yamlKeys: Array.from(yamlKeys).sort() };
}

export function buildMatrixModel(opts = {}) {
  const repoRoot = opts.repoRoot ?? repoRootFromCwd();
  const configBase = path.join(
    repoRoot, "modules", "configuration", "src", "main", "java",
    "io", "justsearch", "configuration",
  );

  const envRegistryPath = opts.envRegistryPath ?? path.join(configBase, "EnvRegistry.java");
  const configKeyPath = opts.configKeyPath ?? path.join(configBase, "ConfigKey.java");
  const builderPath = opts.builderPath ?? path.join(configBase, "resolved", "ResolvedConfigBuilder.java");

  const envRegistry = parseEnvRegistry(envRegistryPath);
  const configKeys = parseConfigKeys(configKeyPath);
  const yamlContrib = parseYamlContributions(builderPath);
  const yamlKeySet = new Set(yamlContrib.yamlKeys);

  const rows = [];

  // EnvRegistry entries: operator-facing, have env var and sysprop
  for (const entry of envRegistry.entries) {
    const hasYaml = yamlKeySet.has(entry.sysprop);
    rows.push({
      yamlKey: hasYaml ? entry.sysprop : "",
      envVar: entry.envVar,
      sysprop: entry.sysprop,
      envRegistryConstant: entry.constant,
      ownerModule: "modules/configuration (ResolvedConfigBuilder)",
      precedenceNotes: hasYaml
        ? "YAML > sysprop > env > default"
        : "sysprop > env > default",
    });
  }

  // ConfigKey entries: YAML-only, no env var or sysprop
  for (const entry of configKeys.entries) {
    rows.push({
      yamlKey: entry.configKey,
      envVar: "",
      sysprop: "",
      envRegistryConstant: "",
      ownerModule: "modules/configuration (ResolvedConfigBuilder)",
      precedenceNotes: "YAML > default",
    });
  }

  rows.sort((a, b) => {
    const aKey = a.yamlKey || a.sysprop || "~";
    const bKey = b.yamlKey || b.sysprop || "~";
    return aKey.localeCompare(bKey);
  });

  return {
    generatedAt: new Date().toISOString(),
    repoRoot,
    envRegistryPath: path.relative(repoRoot, envRegistryPath).replaceAll("\\", "/"),
    configKeyPath: path.relative(repoRoot, configKeyPath).replaceAll("\\", "/"),
    builderPath: path.relative(repoRoot, builderPath).replaceAll("\\", "/"),
    yamlKeyCount: yamlContrib.yamlKeys.length,
    envSyspropPairCount: envRegistry.entries.length,
    configKeyCount: configKeys.entries.length,
    rows,
  };
}

function mdCell(value) {
  const normalized = value && value.trim() ? value : "-";
  return normalized.replaceAll("|", "\\|");
}

export function renderMatrixMarkdown(model) {
  const date = model.generatedAt.slice(0, 10);
  const lines = [];
  lines.push("---");
  lines.push('title: Runtime Config Ownership Matrix');
  lines.push("type: reference");
  lines.push("status: stable");
  lines.push(
    'description: "Canonical YAML/env/sysprop ownership and precedence map."',
  );
  lines.push("---");
  lines.push("");
  lines.push("# Runtime Config Ownership Matrix");
  lines.push("");
  lines.push(
    `Generated from \`${model.envRegistryPath}\`, \`${model.configKeyPath}\`, and \`${model.builderPath}\` on ${date}.`,
  );
  lines.push("");
  lines.push("Precedence note:");
  lines.push("1. `YAML > sysprop > env > default` where a YAML key and env/sysprop fallback both exist.");
  lines.push("2. `YAML > default` for YAML-only keys (ConfigKey entries, no env var override).");
  lines.push("3. `sysprop > env > default` for env/sysprop-only runtime knobs.");
  lines.push("");
  lines.push("| YAML key | Env var | System property | EnvRegistry constant | Owner module | Precedence notes |");
  lines.push("| :--- | :--- | :--- | :--- | :--- | :--- |");
  for (const row of model.rows) {
    lines.push(
      `| ${mdCell(row.yamlKey)} | ${mdCell(row.envVar)} | ${mdCell(row.sysprop)} | ${mdCell(row.envRegistryConstant)} | ${mdCell(row.ownerModule)} | ${mdCell(row.precedenceNotes)} |`,
    );
  }
  lines.push("");
  return lines.join("\n");
}

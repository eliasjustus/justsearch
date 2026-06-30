import fs from "node:fs";
import path from "node:path";
import { buildMatrixModel } from "./runtime-config-matrix-lib.mjs";

const DOC_PATH = "docs/reference/configuration/runtime-config-ownership-matrix.md";

function parseMarkdownRows(markdown) {
  const rows = [];
  for (const line of markdown.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) {
      continue;
    }
    if (/^\|\s*:?-{2,}/.test(trimmed)) {
      continue;
    }
    const cells = trimmed
      .slice(1, -1)
      .split("|")
      .map((c) => c.trim().replaceAll("\\|", "|"));
    rows.push(cells);
  }
  return rows;
}

function normalizeCell(cell) {
  if (!cell) {
    return "";
  }
  const v = cell.trim();
  return v === "-" || v === "�" ? "" : v;
}

function main() {
  if (!fs.existsSync(DOC_PATH)) {
    console.error(`runtime-config-matrix verify: missing doc ${DOC_PATH}`);
    process.exit(1);
  }

  const model = buildMatrixModel();
  const markdown = fs.readFileSync(DOC_PATH, "utf8");
  const tableRows = parseMarkdownRows(markdown);
  if (tableRows.length < 2) {
    console.error("runtime-config-matrix verify: table not found");
    process.exit(1);
  }

  const [header, ...dataRows] = tableRows;
  const headerIdx = new Map(header.map((h, i) => [h.toLowerCase(), i]));
  const yamlIdx = headerIdx.get("yaml key");
  const envIdx = headerIdx.get("env var");
  const sysIdx = headerIdx.get("system property");
  if (yamlIdx == null || envIdx == null || sysIdx == null) {
    console.error(
      "runtime-config-matrix verify: required columns missing (YAML key / Env var / System property)",
    );
    process.exit(1);
  }

  const docYamlKeys = new Set();
  const docPairs = new Set();
  for (const row of dataRows) {
    const yaml = normalizeCell(row[yamlIdx] ?? "");
    const env = normalizeCell(row[envIdx] ?? "");
    const sys = normalizeCell(row[sysIdx] ?? "");
    if (yaml) {
      docYamlKeys.add(yaml);
    }
    if (env && sys) {
      docPairs.add(`${env}|${sys}`);
    }
  }

  // The doc is generated from model.rows, so the verify invariant is "every
  // YAML key / env+sysprop pair the model renders appears in the doc table".
  // (buildMatrixModel exposes rows + aggregate counts; it does not expose the
  // pre-refactor model.yamlKeys / model.envSyspropPairs arrays.)
  const modelYamlKeys = [...new Set(model.rows.map((r) => r.yamlKey).filter(Boolean))];
  const modelPairs = [
    ...new Set(
      model.rows.filter((r) => r.envVar && r.sysprop).map((r) => `${r.envVar}|${r.sysprop}`),
    ),
  ];

  const missingYaml = modelYamlKeys.filter((k) => !docYamlKeys.has(k));
  const missingPairs = modelPairs.filter((k) => !docPairs.has(k));

  if (missingYaml.length || missingPairs.length) {
    console.error("runtime-config-matrix verify: FAIL");
    if (missingYaml.length) {
      console.error(`- missing YAML keys (${missingYaml.length})`);
      for (const key of missingYaml.slice(0, 50)) {
        console.error(`  - ${key}`);
      }
    }
    if (missingPairs.length) {
      console.error(`- missing env/sysprop pairs (${missingPairs.length})`);
      for (const key of missingPairs.slice(0, 50)) {
        const [env, sys] = key.split("|");
        console.error(`  - ${env} | ${sys}`);
      }
    }
    process.exit(1);
  }

  const outPath = path.join(
    "tmp",
    "agent-evidence",
    "_summaries",
    "runtime-config-ownership-matrix.verify.json",
  );
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(
    outPath,
    `${JSON.stringify(
      {
        generated_at: new Date().toISOString(),
        yaml_key_count: model.yamlKeyCount,
        env_sysprop_pair_count: model.envSyspropPairCount,
        doc_row_count: dataRows.length,
      },
      null,
      2,
    )}\n`,
    "utf8",
  );

  console.log(
    `runtime-config-matrix verify: OK (yaml=${model.yamlKeyCount}, pairs=${model.envSyspropPairCount}, rows=${dataRows.length})`,
  );
}

main();


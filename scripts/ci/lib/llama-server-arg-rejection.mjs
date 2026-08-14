#!/usr/bin/env node
/**
 * The one JS-side reader of the declared llama-server launch-argument rejection marker
 * (governance/llama-server-arg-rejection.v1.json, tempdoc 835). No copy of the string lives here:
 * the register is the source, and the runtime detector (LlamaServerOps) is pinned to the same
 * register by a Java test. Match the prefix — the suffix is per-build wording
 * ("invalid value" on b8185, "invalid stoi argument" on b8571).
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REGISTER_PATH = path.resolve(HERE, '..', '..', '..', 'governance', 'llama-server-arg-rejection.v1.json');

let cachedMarker = null;

/** The declared rejection marker (prefix) for `--reasoning-budget`. Throws if the register is unreadable. */
export function reasoningBudgetRejectionMarker() {
  if (cachedMarker == null) {
    const doc = JSON.parse(fs.readFileSync(REGISTER_PATH, 'utf8'));
    const marker = doc?.reasoningBudget?.rejectionMarker;
    if (typeof marker !== 'string' || marker.length === 0) {
      throw new Error(`Missing reasoningBudget.rejectionMarker in ${REGISTER_PATH}`);
    }
    cachedMarker = marker;
  }
  return cachedMarker;
}

/** True when llama-server output shows this build rejecting the `--reasoning-budget` argument. */
export function isReasoningBudgetRejection(serverOutput) {
  if (typeof serverOutput !== 'string' || serverOutput.length === 0) return false;
  return serverOutput.includes(reasoningBudgetRejectionMarker());
}

export const REASONING_BUDGET_REGISTER_PATH = REGISTER_PATH;

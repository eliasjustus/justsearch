/**
 * Repo-local DCO gate.
 *
 * Checks the commits introduced by a pull request or push for a Signed-off-by trailer.
 * Node stdlib only so the workflow does not depend on a third-party DCO action.
 */

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import process from "node:process";

const ZERO_SHA = /^0{40}$/;
const SIGNED_OFF_BY = /^Signed-off-by:\s+.+ <[^<>\s@]+@[^<>\s]+>$/im;

function git(args) {
  return execFileSync("git", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
}

function hasCommit(sha) {
  try {
    git(["cat-file", "-e", `${sha}^{commit}`]);
    return true;
  } catch {
    return false;
  }
}

function readEventPayload() {
  const eventPath = process.env.GITHUB_EVENT_PATH;
  if (!eventPath || !fs.existsSync(eventPath)) return {};
  return JSON.parse(fs.readFileSync(eventPath, "utf8"));
}

function checkoutMergeRange() {
  const parts = git(["rev-list", "--parents", "-n", "1", "HEAD"]).split(/\s+/);
  if (parts.length >= 3) return [`${parts[1]}..${parts[2]}`];
  return ["HEAD"];
}

function eventRange() {
  if (process.env.CHECK_DCO_RANGE) return [process.env.CHECK_DCO_RANGE];

  const event = process.env.GITHUB_EVENT_NAME || "";
  const payload = readEventPayload();

  if (event === "pull_request" || event === "pull_request_target") {
    const base = payload.pull_request?.base?.sha;
    const head = payload.pull_request?.head?.sha;
    if (base && head && hasCommit(base) && hasCommit(head)) return [`${base}..${head}`];
    return checkoutMergeRange();
  }

  if (event === "push") {
    const before = payload.before;
    const after = payload.after;
    if (before && after && !ZERO_SHA.test(before) && hasCommit(before) && hasCommit(after)) {
      return [`${before}..${after}`];
    }
    if (after && hasCommit(after)) return ["--max-count=1", after];
  }

  if (hasCommit("origin/main") && git(["rev-list", "--count", "origin/main..HEAD"]) !== "0") {
    return ["origin/main..HEAD"];
  }

  return [];
}

function commitsForRange(rangeArgs) {
  if (rangeArgs.length === 0) return [];
  const out = git(["rev-list", "--reverse", ...rangeArgs]);
  return out ? out.split(/\r?\n/).filter(Boolean) : [];
}

function commitMessage(sha) {
  return git(["log", "--format=%B", "-n", "1", sha]);
}

function commitSubject(sha) {
  return git(["log", "--format=%s", "-n", "1", sha]);
}

function main() {
  const rangeArgs = eventRange();
  const commits = commitsForRange(rangeArgs);
  if (commits.length === 0) {
    console.log(`check-dco: OK (no commits in range: ${rangeArgs.join(" ")})`);
    return;
  }

  const failures = [];
  for (const sha of commits) {
    const message = commitMessage(sha);
    if (!SIGNED_OFF_BY.test(message)) {
      failures.push(`${sha.slice(0, 12)} ${commitSubject(sha)}`);
    }
  }

  if (failures.length > 0) {
    console.error(`check-dco: FAIL (${failures.length}/${commits.length} commits missing Signed-off-by trailer)`);
    for (const failure of failures) console.error(`  - ${failure}`);
    console.error("Add a DCO trailer with `git commit -s` or `git commit --amend -s`.");
    process.exitCode = 1;
    return;
  }

  console.log(`check-dco: OK (${commits.length} commits checked; range: ${rangeArgs.join(" ")})`);
}

main();

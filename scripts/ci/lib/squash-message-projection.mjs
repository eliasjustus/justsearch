import crypto from 'node:crypto';

import MarkdownIt from 'markdown-it';

import {
  findSessionIdValues,
  isPlausibleSessionId,
  SESSION_ID_KEY,
} from '../../agent-analytics/merge-links.mjs';

export const PROJECTION_KIND = 'justsearch-publication-record.v3';
export const REVIEW_MARKER_KIND = 'justsearch-review-record:v1';
export const AUTHORSHIP_CLASSES = new Set(['agent', 'human', 'mixed', 'trusted-bot']);
export const TRUSTED_BOT_LOGINS = new Set(['dependabot[bot]']);
export const TRUSTED_REVIEW_ASSOCIATIONS = new Set(['OWNER', 'MEMBER', 'COLLABORATOR']);

const REQUIRED_REVIEW_HEADINGS = ['Scope and risk', 'Verification evidence', 'Review state'];
const PROCESS_MARKER_RE = /\b(?:wip|work in progress|do not publish|review round \d+|base\/stack state)\b/i;
const SHA_RE = /\b[0-9a-f]{40}\b/i;
const TASK_BOX_RE = /^\s*[-*+]\s+\[[ xX]\]/m;
const INTERNAL_LINE_RE = /^(?:Stack|Base):\s+\S/im;
const INTERNAL_FENCE_RE = /^```(?:stack|base|stack-log|base-log)\s*$/im;
const SESSION_DECLARATION_LINE_RE = /^Session-Id:[^\r\n]*$/i;
const OPAQUE_SESSION_DECLARATION_RE = /^\s*(?:>\s*|[-*+]\s+)?Session-Id:[^\r\n]*$/i;
const MARKER_RE = /^<!-- justsearch-review-record:v1 pr=(\d+) head=([0-9a-f]{40}) public-body-sha256=([0-9a-f]{64}) -->$/;
const REVIEW_RESIDUE_RE = /^(?:#{2,3}\s+(?:Review record|Scope and risk|Verification evidence|Review state|Testing)\s*|(?:Authorship|Testing|Tests|Verification):\s*\S.*)$/im;
const TEMPLATE_RESIDUE_RE = /(?:Explain why this durable change was needed|Describe one observable outcome|Describe verification or `Not run: <reason>`|<session-uuid>)/i;
const REVIEW_TEMPLATE_RESIDUE_RE = /(?:agent \| human \| mixed \| trusted-bot|Describe the affected surface|List reproducible checks and results|State unresolved findings and decisions)/i;

const md = new MarkdownIt({ html: true });

function finding(id, message) {
  return { id, message };
}

export function normalizeLf(value) {
  return String(value ?? '').replace(/\r\n?/g, '\n');
}

export function sha256(value) {
  return crypto.createHash('sha256').update(normalizeLf(value), 'utf8').digest('hex');
}

function headingTokens(markdown, tokens = md.parse(markdown, {})) {
  const headings = [];
  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i];
    if (token.type !== 'heading_open' || token.level !== 0 || !token.map) continue;
    const inline = tokens[i + 1];
    if (!inline || inline.type !== 'inline') continue;
    headings.push({ tag: token.tag, name: inline.content.trim(), start: token.map[0], end: token.map[1] });
  }
  return headings;
}

function topLevelAuthorshipDeclarations(reviewText) {
  const tokens = md.parse(reviewText, {});
  const values = [];
  for (let i = 0; i < tokens.length; i += 1) {
    if (tokens[i].type !== 'paragraph_open' || tokens[i].level !== 0) continue;
    const inline = tokens[i + 1];
    if (!inline || inline.type !== 'inline') continue;
    const match = /^Authorship:\s*(\S.*?)\s*$/i.exec(inline.content);
    if (match) values.push(match[1]);
  }
  return values;
}

function trimBoundaryBlankLines(lines) {
  let start = 0;
  let end = lines.length;
  while (start < end && lines[start].trim() === '') start += 1;
  while (end > start && lines[end - 1].trim() === '') end -= 1;
  return lines.slice(start, end);
}

function subsectionContent(lines, headings, heading, boundary) {
  const next = headings.find((candidate) => candidate.start > heading.start && candidate.start < boundary && candidate.tag === 'h3');
  return trimBoundaryBlankLines(lines.slice(heading.end, next?.start ?? boundary)).join('\n');
}

function rootSessionDeclarations(body, errors) {
  const lines = body.split('\n');
  const tokens = md.parse(body, {});
  const rootParagraphLines = new Set();
  for (const token of tokens) {
    if (token.type !== 'paragraph_open' || token.level !== 0 || !token.map) continue;
    for (let line = token.map[0]; line < token.map[1]; line += 1) rootParagraphLines.add(line);
  }
  const declarations = [];
  for (let line = 0; line < lines.length; line += 1) {
    if (!OPAQUE_SESSION_DECLARATION_RE.test(lines[line])) continue;
    if (!SESSION_DECLARATION_LINE_RE.test(lines[line]) || !rootParagraphLines.has(line)) {
      errors.push(finding('session-id-not-root-content', `${SESSION_ID_KEY}: declarations inside fences, raw HTML, blockquotes, or lists do not establish authorship.`));
      continue;
    }
    declarations.push(lines[line]);
  }
  const values = findSessionIdValues(declarations.join('\n'));
  if (declarations.length !== values.length) errors.push(finding('malformed-session-id', `Every ${SESSION_ID_KEY}: declaration must have a non-empty value.`));
  return { declarations, values };
}

function validateSessionDeclarations(body, authorship, errors) {
  const { declarations, values } = rootSessionDeclarations(body, errors);
  for (const value of values) {
    if (!isPlausibleSessionId(value)) errors.push(finding('malformed-session-id', `Invalid ${SESSION_ID_KEY} value: ${JSON.stringify(value)}.`));
  }
  const valid = [...new Set(values.filter(isPlausibleSessionId))];
  if ((authorship === 'agent' || authorship === 'mixed') && valid.length === 0) {
    errors.push(finding('missing-session-id', `${authorship} work requires at least one valid ${SESSION_ID_KEY}: declaration in the public PR body.`));
  }
  if ((authorship === 'human' || authorship === 'trusted-bot') && declarations.length > 0) {
    errors.push(finding('unexpected-session-id', `${authorship} work must omit ${SESSION_ID_KEY}: declarations; use mixed when an agent materially contributed.`));
  }
  return valid;
}

function normalizePullRequest(pr) {
  const number = Number(pr?.number);
  const title = String(pr?.title ?? '').trim();
  return {
    number: Number.isInteger(number) && number > 0 ? number : null,
    title,
    body: normalizeLf(pr?.body),
    url: pr?.url ?? pr?.html_url ?? null,
    headSha: pr?.headSha ?? pr?.headRefOid ?? pr?.head?.sha ?? null,
    updatedAt: pr?.updatedAt ?? pr?.updated_at ?? null,
    authorLogin: pr?.authorLogin ?? pr?.author?.login ?? pr?.user?.login ?? null,
    expectedLandedSubject: String(pr?.expectedLandedSubject ?? pr?.viewerMergeHeadlineText ?? (number ? `${title} (#${number})` : title)).trim(),
  };
}

export function reviewMarker({ prNumber, headSha, publicBodySha256 }) {
  return `<!-- ${REVIEW_MARKER_KIND} pr=${prNumber} head=${headSha} public-body-sha256=${publicBodySha256} -->`;
}

export function buildManagedReviewBody({ pr, reviewBody }) {
  const pullRequest = normalizePullRequest(pr);
  return `${reviewMarker({ prNumber: pullRequest.number, headSha: pullRequest.headSha, publicBodySha256: sha256(pullRequest.body) })}\n${normalizeLf(reviewBody).trim()}\n`;
}

export function findManagedReviewComments(comments) {
  return (comments ?? []).filter((comment) => normalizeLf(comment?.body).includes(`<!-- ${REVIEW_MARKER_KIND} `));
}

function validatePublicBody(pullRequest, errors, warnings) {
  const body = pullRequest.body;
  const nonblankLines = body === '' ? 0 : body.split('\n').filter((line) => line.trim() !== '').length;
  if (!pullRequest.title) errors.push(finding('missing-title', 'PR title is empty.'));
  if (!body.trim()) errors.push(finding('missing-public-body', 'PR body is empty.'));
  if (!pullRequest.headSha || !/^[0-9a-f]{40}$/i.test(pullRequest.headSha)) errors.push(finding('invalid-head-sha', 'PR head SHA must be a 40-character hexadecimal object ID.'));
  if (pullRequest.expectedLandedSubject.length > 72) errors.push(finding('subject-too-long', `Projected subject is ${pullRequest.expectedLandedSubject.length} characters; maximum is 72.`));
  if (pullRequest.title.length > 60) warnings.push(finding('title-above-target', `PR title is ${pullRequest.title.length} characters; the publication target is 60 before GitHub's suffix.`));
  if (body.length > 2000 || nonblankLines > 32) errors.push(finding('public-body-too-large', `Public PR body is ${body.length} characters / ${nonblankLines} nonblank lines; limits are 2000 / 32.`));
  else if (body.length > 1200 || nonblankLines > 20) warnings.push(finding('public-body-large', `Public PR body is ${body.length} characters / ${nonblankLines} nonblank lines; review above 1200 / 20.`));
  if (TASK_BOX_RE.test(body)) errors.push(finding('public-checklist', 'Public PR body contains a visible Markdown task box.'));
  if (/<!--/.test(body)) errors.push(finding('public-html-comment', 'Public PR body contains an HTML comment.'));
  if (/<details\b/i.test(body)) errors.push(finding('public-details', 'Public PR body contains an HTML <details> block.'));
  if (PROCESS_MARKER_RE.test(`${pullRequest.expectedLandedSubject}\n${body}`)) errors.push(finding('public-process-marker', 'Public title/body contains a WIP, review-round, or stack-state marker.'));
  if (INTERNAL_LINE_RE.test(body) || INTERNAL_FENCE_RE.test(body)) errors.push(finding('public-stack-base-log', 'Public PR body contains a reserved stack/base log marker.'));
  if (/Generated with Claude Code|claude\.ai\/code\/session/i.test(body)) errors.push(finding('public-provider-banner', 'Public PR body contains a provider banner or provider session URL.'));
  if (REVIEW_RESIDUE_RE.test(body)) errors.push(finding('public-review-residue', 'Public PR body contains review-record structure.'));
  if (TEMPLATE_RESIDUE_RE.test(body)) errors.push(finding('public-template-residue', 'Public PR body still contains pull-request template placeholders.'));
  if (SHA_RE.test(body)) warnings.push(finding('public-raw-sha', 'Public PR body contains a raw 40-character SHA; confirm it is durable context.'));
  return { body, nonblankLines };
}

function validateReviewComment(pullRequest, reviewComment, errors) {
  if (!reviewComment) {
    errors.push(finding('missing-review-record', 'Expected exactly one managed review-record comment; found 0.'));
    return { authorship: null, marker: null, association: null };
  }
  const body = normalizeLf(reviewComment.body);
  if (body.length > 12_000) errors.push(finding('review-record-too-large', `Managed review record is ${body.length} characters; maximum is 12000.`));
  if (REVIEW_TEMPLATE_RESIDUE_RE.test(body)) errors.push(finding('review-template-residue', 'Managed review record still contains template choices or placeholder prose.'));
  const association = reviewComment.author_association ?? reviewComment.authorAssociation ?? null;
  if (reviewComment.id != null && !TRUSTED_REVIEW_ASSOCIATIONS.has(association)) {
    errors.push(finding('untrusted-review-owner', `Managed review record author association must be one of: ${[...TRUSTED_REVIEW_ASSOCIATIONS].join(', ')}.`));
  }
  const lines = body.split('\n');
  const markerMatch = MARKER_RE.exec(lines[0] ?? '');
  if (!markerMatch) {
    errors.push(finding('invalid-review-marker', 'Managed review record must begin with the exact versioned metadata marker.'));
    return { authorship: null, marker: null, association };
  }
  const marker = { prNumber: Number(markerMatch[1]), headSha: markerMatch[2], publicBodySha256: markerMatch[3] };
  if (marker.prNumber !== pullRequest.number) errors.push(finding('review-pr-mismatch', `Review record covers PR #${marker.prNumber}, not #${pullRequest.number}.`));
  if (marker.headSha !== pullRequest.headSha) errors.push(finding('review-head-stale', `Review record covers head ${marker.headSha}, not ${pullRequest.headSha}.`));
  if (marker.publicBodySha256 !== sha256(pullRequest.body)) errors.push(finding('review-public-body-stale', 'Review record public-body fingerprint does not match the current PR body.'));
  if ((body.match(/<!-- justsearch-review-record:v1 /g) ?? []).length !== 1) errors.push(finding('review-marker-cardinality', 'Managed review record must contain exactly one versioned marker.'));

  const reviewText = lines.slice(1).join('\n');
  const headings = headingTokens(reviewText);
  const reviewHeadings = headings.filter((heading) => heading.tag === 'h2' && heading.name === 'Review record');
  if (reviewHeadings.length !== 1) {
    errors.push(finding('review-section-cardinality', `Expected exactly one top-level ## Review record section; found ${reviewHeadings.length}.`));
    return { authorship: null, marker, association };
  }
  const reviewHeading = reviewHeadings[0];
  const nextH2 = headings.find((heading) => heading.tag === 'h2' && heading.start > reviewHeading.start);
  const boundary = nextH2?.start ?? reviewText.split('\n').length;
  const reviewLines = reviewText.split('\n');
  if (trimBoundaryBlankLines(reviewLines.slice(0, reviewHeading.start)).length > 0) {
    errors.push(finding('unexpected-review-preamble', 'Managed review record must place ## Review record immediately after its marker.'));
  }
  if (nextH2) errors.push(finding('unexpected-review-section', `Unexpected top-level section after Review record: ${nextH2.name}.`));
  const reviewSectionText = reviewLines.slice(reviewHeading.end, boundary).join('\n');
  const declarations = topLevelAuthorshipDeclarations(reviewSectionText);
  let authorship = null;
  if (declarations.length !== 1) errors.push(finding('authorship-cardinality', `Expected exactly one Authorship declaration in Review record; found ${declarations.length}.`));
  else {
    authorship = declarations[0].toLowerCase();
    if (!AUTHORSHIP_CLASSES.has(authorship)) errors.push(finding('invalid-authorship', `Authorship must be one of: ${[...AUTHORSHIP_CLASSES].join(', ')}.`));
  }
  for (const name of REQUIRED_REVIEW_HEADINGS) {
    const matches = headings.filter((heading) => heading.tag === 'h3' && heading.name === name && heading.start > reviewHeading.start && heading.start < boundary);
    if (matches.length !== 1) errors.push(finding('review-heading-cardinality', `Expected exactly one ### ${name} under Review record; found ${matches.length}.`));
    else if (!subsectionContent(reviewLines, headings, matches[0], boundary).trim()) errors.push(finding('empty-review-section', `### ${name} must contain current evidence or an explicit none/not-run statement.`));
  }
  return { authorship, marker, association };
}

export function buildSquashMessageProjection({ repoSlug = null, pr, reviewComment = null }) {
  const pullRequest = normalizePullRequest(pr);
  const errors = [];
  const warnings = [];
  const { body, nonblankLines } = validatePublicBody(pullRequest, errors, warnings);
  const { authorship, marker, association } = validateReviewComment(pullRequest, reviewComment, errors);
  const sessionIds = AUTHORSHIP_CLASSES.has(authorship) ? validateSessionDeclarations(body, authorship, errors) : [];
  if (authorship === 'trusted-bot' && !TRUSTED_BOT_LOGINS.has(pullRequest.authorLogin)) errors.push(finding('untrusted-bot-actor', `trusted-bot is limited to PRs authored by: ${[...TRUSTED_BOT_LOGINS].join(', ')}.`));
  return {
    kind: PROJECTION_KIND,
    repo: repoSlug,
    source: { prNumber: pullRequest.number, headSha: pullRequest.headSha, updatedAt: pullRequest.updatedAt },
    authorship,
    sessionIds,
    expectedLandedSubject: pullRequest.expectedLandedSubject,
    body,
    publicBodySha256: sha256(body),
    bodyChars: body.length,
    bodyLines: body === '' ? 0 : body.split('\n').length,
    bodyNonblankLines: nonblankLines,
    review: reviewComment ? {
      id: reviewComment.id ?? null,
      url: reviewComment.html_url ?? reviewComment.url ?? null,
      authorLogin: reviewComment.user?.login ?? reviewComment.authorLogin ?? null,
      authorAssociation: association,
      marker,
    } : null,
    warnings,
    errors,
  };
}

export function buildPublicSquashRecord({ repoSlug = null, pr }) {
  const pullRequest = normalizePullRequest(pr);
  const errors = [];
  const warnings = [];
  const { body, nonblankLines } = validatePublicBody(pullRequest, errors, warnings);
  return {
    kind: 'justsearch-public-squash-record.v1',
    repo: repoSlug,
    source: { prNumber: pullRequest.number, headSha: pullRequest.headSha, updatedAt: pullRequest.updatedAt },
    expectedLandedSubject: pullRequest.expectedLandedSubject,
    body,
    publicBodySha256: sha256(body),
    bodyChars: body.length,
    bodyLines: body === '' ? 0 : body.split('\n').length,
    bodyNonblankLines: nonblankLines,
    warnings,
    errors,
  };
}

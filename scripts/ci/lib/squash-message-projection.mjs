import MarkdownIt from 'markdown-it';

import {
  findSessionIdValues,
  isPlausibleSessionId,
  SESSION_ID_KEY,
} from '../../agent-analytics/merge-links.mjs';

export const PROJECTION_KIND = 'justsearch-squash-message-projection.v2';
export const AUTHORSHIP_CLASSES = new Set(['agent', 'human', 'mixed', 'trusted-bot']);
export const TRUSTED_BOT_LOGINS = new Set(['dependabot[bot]']);

const REQUIRED_REVIEW_HEADINGS = ['Scope and risk', 'Verification evidence', 'Review state'];
const PROCESS_MARKER_RE = /\b(?:wip|work in progress|do not publish|review round \d+|base\/stack state)\b/i;
const SHA_RE = /\b[0-9a-f]{40}\b/i;
const TASK_BOX_RE = /^\s*[-*+]\s+\[[ xX]\]/m;
const INTERNAL_LINE_RE = /^(?:Stack|Base):\s+\S/im;
const INTERNAL_FENCE_RE = /^```(?:stack|base|stack-log|base-log)\s*$/im;
const SESSION_DECLARATION_LINE_RE = /^Session-Id:[^\r\n]*$/i;
const OPAQUE_SESSION_DECLARATION_RE = /^\s*(?:>\s*|[-*+]\s+)?Session-Id:[^\r\n]*$/i;

const md = new MarkdownIt({ html: true });

function finding(id, message) {
  return { id, message };
}

function normalizeLf(value) {
  return String(value ?? '').replace(/\r\n?/g, '\n');
}

function headingTokens(markdown, tokens = md.parse(markdown, {})) {
  const headings = [];
  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i];
    if (token.type !== 'heading_open' || token.level !== 0 || !token.map) continue;
    const inline = tokens[i + 1];
    if (!inline || inline.type !== 'inline') continue;
    headings.push({
      tag: token.tag,
      name: inline.content.trim(),
      start: token.map[0],
      end: token.map[1],
    });
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

function sectionText(lines, heading, nextHeading) {
  return trimBoundaryBlankLines(lines.slice(heading.end, nextHeading?.start ?? lines.length)).join('\n');
}

function namedTopLevelHeadings(headings, name) {
  return headings.filter((heading) => heading.tag === 'h2' && heading.name === name);
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
  if (declarations.length !== values.length) {
    errors.push(finding('malformed-session-id', `Every ${SESSION_ID_KEY}: declaration must have a non-empty value.`));
  }
  return { declarations, values };
}

function validateSessionDeclarations(body, authorship, errors) {
  const { declarations, values } = rootSessionDeclarations(body, errors);
  for (const value of values) {
    if (!isPlausibleSessionId(value)) {
      errors.push(finding('malformed-session-id', `Invalid ${SESSION_ID_KEY} value: ${JSON.stringify(value)}.`));
    }
  }
  const valid = [...new Set(values.filter(isPlausibleSessionId))];
  if ((authorship === 'agent' || authorship === 'mixed') && valid.length === 0) {
    errors.push(finding('missing-session-id', `${authorship} work requires at least one valid ${SESSION_ID_KEY}: declaration in Public commit.`));
  }
  if ((authorship === 'human' || authorship === 'trusted-bot') && declarations.length > 0) {
    errors.push(finding('unexpected-session-id', `${authorship} work must omit ${SESSION_ID_KEY}: declarations; use mixed when an agent materially contributed.`));
  }
  return valid;
}

function normalizePullRequest(pr) {
  return {
    number: pr?.number ?? null,
    title: String(pr?.title ?? '').trim(),
    body: normalizeLf(pr?.body),
    url: pr?.url ?? null,
    headRefName: pr?.headRefName ?? null,
    headSha: pr?.headSha ?? pr?.headRefOid ?? null,
    updatedAt: pr?.updatedAt ?? null,
    baseRefName: pr?.baseRefName ?? null,
    isDraft: Boolean(pr?.isDraft),
    state: pr?.state ?? null,
    mergeStateStatus: pr?.mergeStateStatus ?? null,
    isInMergeQueue: Boolean(pr?.isInMergeQueue),
    mergeQueueEntry: pr?.mergeQueueEntry ?? null,
    autoMergeRequest: pr?.autoMergeRequest ?? null,
    authorLogin: pr?.authorLogin ?? pr?.author?.login ?? null,
    expectedLandedSubject: String(pr?.expectedLandedSubject ?? pr?.viewerMergeHeadlineText ?? '').trim(),
  };
}

export function buildSquashMessageProjection({ repoSlug = null, pr }) {
  const pullRequest = normalizePullRequest(pr);
  const lines = pullRequest.body.split('\n');
  const headings = headingTokens(pullRequest.body);
  const publicHeadings = namedTopLevelHeadings(headings, 'Public commit');
  const reviewHeadings = namedTopLevelHeadings(headings, 'Review record');
  const errors = [];
  const warnings = [];

  if (!pullRequest.expectedLandedSubject) {
    errors.push(finding('missing-projected-subject', 'GitHub did not provide viewerMergeHeadlineText(SQUASH); refusing to guess the landed subject.'));
  } else {
    if (pullRequest.expectedLandedSubject.length > 72) {
      errors.push(finding('subject-too-long', `Projected subject is ${pullRequest.expectedLandedSubject.length} characters; maximum is 72.`));
    }
    if (PROCESS_MARKER_RE.test(pullRequest.expectedLandedSubject)) {
      errors.push(finding('subject-process-marker', 'Projected subject contains a WIP, review-round, or stack-state marker.'));
    }
  }
  if (pullRequest.title.length > 60) {
    warnings.push(finding('title-above-target', `PR title is ${pullRequest.title.length} characters; the publication target is 60 before GitHub's suffix.`));
  }

  if (publicHeadings.length !== 1) {
    errors.push(finding('public-section-cardinality', `Expected exactly one top-level ## Public commit section; found ${publicHeadings.length}.`));
  }
  if (reviewHeadings.length !== 1) {
    errors.push(finding('review-section-cardinality', `Expected exactly one top-level ## Review record section; found ${reviewHeadings.length}.`));
  }
  if (publicHeadings.length === 1 && reviewHeadings.length === 1 && publicHeadings[0].start > reviewHeadings[0].start) {
    errors.push(finding('section-order', '## Public commit must precede ## Review record.'));
  }

  const publicHeading = publicHeadings[0] ?? null;
  const nextH2 = publicHeading
    ? headings.find((heading) => heading.tag === 'h2' && heading.start > publicHeading.start)
    : null;
  const body = publicHeading ? sectionText(lines, publicHeading, nextH2) : '';
  const nonblankLines = body === '' ? 0 : body.split('\n').filter((line) => line.trim() !== '').length;

  if (body.length > 2000 || nonblankLines > 32) {
    errors.push(finding('public-body-too-large', `Public commit body is ${body.length} characters / ${nonblankLines} nonblank lines; limits are 2000 / 32.`));
  } else if (body.length > 1200 || nonblankLines > 20) {
    warnings.push(finding('public-body-large', `Public commit body is ${body.length} characters / ${nonblankLines} nonblank lines; review above 1200 / 20.`));
  }
  if (TASK_BOX_RE.test(body)) errors.push(finding('public-checklist', 'Public commit contains a visible Markdown task box.'));
  if (/<!--/.test(body)) errors.push(finding('public-html-comment', 'Public commit contains an HTML comment.'));
  if (/<details\b/i.test(body)) errors.push(finding('public-details', 'Public commit contains an HTML <details> block.'));
  if (PROCESS_MARKER_RE.test(body)) errors.push(finding('public-process-marker', 'Public commit contains a WIP, review-round, or stack-state marker.'));
  if (INTERNAL_LINE_RE.test(body) || INTERNAL_FENCE_RE.test(body)) {
    errors.push(finding('public-stack-base-log', 'Public commit contains a reserved stack/base log marker.'));
  }
  if (/Generated with Claude Code|claude\.ai\/code\/session/i.test(body)) {
    errors.push(finding('public-provider-banner', 'Public commit contains a provider banner or provider session URL.'));
  }
  if (SHA_RE.test(body)) warnings.push(finding('public-raw-sha', 'Public commit contains a raw 40-character SHA; confirm it is durable context.'));

  let authorship = null;
  const reviewHeading = reviewHeadings[0] ?? null;
  if (reviewHeading) {
    const nextReviewH2 = headings.find((heading) => heading.tag === 'h2' && heading.start > reviewHeading.start);
    const boundary = nextReviewH2?.start ?? lines.length;
    const reviewText = trimBoundaryBlankLines(lines.slice(reviewHeading.end, boundary)).join('\n');
    const declarations = topLevelAuthorshipDeclarations(reviewText);
    if (declarations.length !== 1) {
      errors.push(finding('authorship-cardinality', `Expected exactly one Authorship declaration in Review record; found ${declarations.length}.`));
    } else {
      authorship = declarations[0].toLowerCase();
      if (!AUTHORSHIP_CLASSES.has(authorship)) {
        errors.push(finding('invalid-authorship', `Authorship must be one of: ${[...AUTHORSHIP_CLASSES].join(', ')}.`));
      }
    }

    for (const name of REQUIRED_REVIEW_HEADINGS) {
      const matches = headings.filter((heading) => heading.tag === 'h3' && heading.name === name && heading.start > reviewHeading.start && heading.start < boundary);
      if (matches.length !== 1) {
        errors.push(finding('review-heading-cardinality', `Expected exactly one ### ${name} under Review record; found ${matches.length}.`));
      } else if (!subsectionContent(lines, headings, matches[0], boundary).trim()) {
        errors.push(finding('empty-review-section', `### ${name} must contain current evidence or an explicit none/not-run statement.`));
      }
    }
  }

  const sessionIds = AUTHORSHIP_CLASSES.has(authorship)
    ? validateSessionDeclarations(body, authorship, errors)
    : [];
  if (authorship === 'trusted-bot' && !TRUSTED_BOT_LOGINS.has(pullRequest.authorLogin)) {
    errors.push(finding('untrusted-bot-actor', `trusted-bot is limited to: ${[...TRUSTED_BOT_LOGINS].join(', ')}.`));
  }

  const { body: _richReviewBody, ...prSummary } = pullRequest;
  return {
    kind: PROJECTION_KIND,
    repo: repoSlug,
    source: {
      prNumber: pullRequest.number,
      headSha: pullRequest.headSha,
      updatedAt: pullRequest.updatedAt,
    },
    pr: prSummary,
    authorship,
    sessionIds,
    expectedLandedSubject: pullRequest.expectedLandedSubject,
    body,
    bodyChars: body.length,
    bodyLines: body === '' ? 0 : body.split('\n').length,
    bodyNonblankLines: nonblankLines,
    warnings,
    errors,
  };
}

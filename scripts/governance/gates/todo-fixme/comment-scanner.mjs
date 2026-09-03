import { extname } from "node:path";

const MARKER_PATTERN = /\b(?:TODO|FIXME|XXX)\b/g;

function markerCount(text) {
  return (text.match(MARKER_PATTERN) ?? []).length;
}

function skipQuoted(source, start, quote) {
  let i = start + quote.length;
  while (i < source.length) {
    if (source.startsWith(quote, i)) return i + quote.length;
    if (source[i] === "\\") i += 2;
    else i += 1;
  }
  return source.length;
}

function rustRawStringLength(source, start) {
  const match = /^(?:br|r)(#{0,255})"/.exec(source.slice(start));
  if (!match) return 0;
  const terminator = `"${match[1]}`;
  const bodyStart = start + match[0].length;
  const end = source.indexOf(terminator, bodyStart);
  return end === -1 ? source.length - start : end + terminator.length - start;
}

function rustCharEnd(source, start) {
  let i = start + 1;
  if (i >= source.length || source[i] === "\n" || source[i] === "\r")
    return null;
  if (source[i] === "\\") {
    i += 1;
    if (source[i] === "u" && source[i + 1] === "{") {
      const close = source.indexOf("}", i + 2);
      if (close === -1) return null;
      i = close + 1;
    } else {
      i += 1;
    }
  } else {
    const cp = source.codePointAt(i);
    i += cp > 0xffff ? 2 : 1;
  }
  return source[i] === "'" ? i + 1 : null;
}

function countCStyleComments(
  source,
  { javaTextBlocks = false, rust = false } = {},
) {
  let count = 0;
  let i = 0;
  while (i < source.length) {
    if (source.startsWith("//", i)) {
      const end = source.indexOf("\n", i + 2);
      const stop = end === -1 ? source.length : end;
      count += markerCount(source.slice(i + 2, stop));
      i = stop;
      continue;
    }
    if (source.startsWith("/*", i)) {
      const start = i + 2;
      let depth = 1;
      i = start;
      while (i < source.length && depth > 0) {
        if (rust && source.startsWith("/*", i)) {
          depth += 1;
          i += 2;
        } else if (source.startsWith("*/", i)) {
          depth -= 1;
          i += 2;
        } else {
          i += 1;
        }
      }
      count += markerCount(
        source.slice(start, depth === 0 ? i - 2 : source.length),
      );
      continue;
    }
    if (rust) {
      const rawLength = rustRawStringLength(source, i);
      if (rawLength > 0) {
        i += rawLength;
        continue;
      }
      if (source[i] === "'") {
        const charEnd = rustCharEnd(source, i);
        if (charEnd !== null) {
          i = charEnd;
          continue;
        }
      }
    }
    if (javaTextBlocks && source.startsWith('"""', i)) {
      i = skipQuoted(source, i, '"""');
      continue;
    }
    if (source[i] === '"' || (!rust && source[i] === "'")) {
      i = skipQuoted(source, i, source[i]);
      continue;
    }
    i += 1;
  }
  return count;
}

function skipJavaScriptRegex(source, start) {
  let i = start + 1;
  let inCharacterClass = false;
  while (i < source.length) {
    if (source[i] === "\\") {
      i += 2;
    } else if (source[i] === "[") {
      inCharacterClass = true;
      i += 1;
    } else if (source[i] === "]" && inCharacterClass) {
      inCharacterClass = false;
      i += 1;
    } else if (source[i] === "/" && !inCharacterClass) {
      i += 1;
      while (/[A-Za-z]/.test(source[i] ?? "")) i += 1;
      return i;
    } else if (source[i] === "\n" || source[i] === "\r") {
      return null;
    } else {
      i += 1;
    }
  }
  return null;
}

const REGEX_PREFIX_KEYWORDS = new Set([
  "await",
  "case",
  "delete",
  "do",
  "else",
  "in",
  "instanceof",
  "new",
  "return",
  "throw",
  "typeof",
  "void",
  "yield",
]);

function scanJavaScriptTemplate(source, start) {
  let count = 0;
  let i = start + 1;
  while (i < source.length) {
    if (source[i] === "\\") {
      i += 2;
    } else if (source[i] === "`") {
      return { count, next: i + 1 };
    } else if (source.startsWith("${", i)) {
      const expression = scanJavaScriptCode(source, i + 2, true);
      count += expression.count;
      i = expression.next;
    } else {
      i += 1;
    }
  }
  return { count, next: source.length };
}

function scanJavaScriptCode(source, start = 0, stopAtTemplateEnd = false) {
  let count = 0;
  let i = start;
  let braceDepth = 0;
  let expectsExpression = true;
  while (i < source.length) {
    if (source.startsWith("//", i)) {
      const end = source.indexOf("\n", i + 2);
      const stop = end === -1 ? source.length : end;
      count += markerCount(source.slice(i + 2, stop));
      i = stop;
      continue;
    }
    if (source.startsWith("/*", i)) {
      const end = source.indexOf("*/", i + 2);
      const stop = end === -1 ? source.length : end;
      count += markerCount(source.slice(i + 2, stop));
      i = end === -1 ? source.length : end + 2;
      continue;
    }
    if (source[i] === '"' || source[i] === "'") {
      i = skipQuoted(source, i, source[i]);
      expectsExpression = false;
      continue;
    }
    if (source[i] === "`") {
      const template = scanJavaScriptTemplate(source, i);
      count += template.count;
      i = template.next;
      expectsExpression = false;
      continue;
    }
    if (source[i] === "/" && expectsExpression) {
      const regexEnd = skipJavaScriptRegex(source, i);
      if (regexEnd !== null) {
        i = regexEnd;
        expectsExpression = false;
        continue;
      }
    }
    if (source[i] === "{") {
      braceDepth += 1;
      expectsExpression = true;
      i += 1;
      continue;
    }
    if (source[i] === "}") {
      if (stopAtTemplateEnd && braceDepth === 0) return { count, next: i + 1 };
      braceDepth = Math.max(0, braceDepth - 1);
      expectsExpression = false;
      i += 1;
      continue;
    }
    if (/[A-Za-z_$]/.test(source[i])) {
      const match = /^[A-Za-z_$][A-Za-z0-9_$]*/.exec(source.slice(i));
      i += match[0].length;
      expectsExpression = REGEX_PREFIX_KEYWORDS.has(match[0]);
      continue;
    }
    if (/[0-9]/.test(source[i])) {
      const match =
        /^(?:0[xob][0-9a-f]+|[0-9]+(?:\.[0-9]*)?(?:e[+-]?[0-9]+)?)/i.exec(
          source.slice(i),
        );
      i += match?.[0].length ?? 1;
      expectsExpression = false;
      continue;
    }
    if (!/\s/.test(source[i])) {
      expectsExpression = !/[)\]]/.test(source[i]);
    }
    i += 1;
  }
  return { count, next: source.length };
}

function countJavaScriptComments(source) {
  return scanJavaScriptCode(source).count;
}

function pythonStringAt(source, start) {
  if (start > 0 && /[A-Za-z0-9_]/.test(source[start - 1])) return null;
  return /^(?:(?:br|rb|fr|rf|r|u|b|f))?("""|'''|"|')/i.exec(
    source.slice(start),
  );
}

function countPythonComments(source) {
  let count = 0;
  let i = 0;
  while (i < source.length) {
    const string = pythonStringAt(source, i);
    if (string) {
      const delimiter = string[1];
      const quoteStart = i + string[0].length - delimiter.length;
      i = skipQuoted(source, quoteStart, delimiter);
      continue;
    }
    if (source[i] === "#") {
      const end = source.indexOf("\n", i + 1);
      const stop = end === -1 ? source.length : end;
      count += markerCount(source.slice(i + 1, stop));
      i = stop;
      continue;
    }
    i += 1;
  }
  return count;
}

function skipPowerShellString(source, start, quote) {
  let i = start + 1;
  while (i < source.length) {
    if (quote === "'" && source.startsWith("''", i)) {
      i += 2;
    } else if (quote === '"' && source[i] === "`") {
      i += 2;
    } else if (source[i] === quote) {
      return i + 1;
    } else {
      i += 1;
    }
  }
  return source.length;
}

function skipPowerShellHereString(source, start, quote) {
  const terminator = quote === "'" ? "\n'@" : '\n"@';
  const end = source.indexOf(terminator, start + 2);
  return end === -1 ? source.length : end + terminator.length;
}

function powerShellHereTerminatorAt(source, index, quote) {
  return (
    (index === 0 || source[index - 1] === "\n") &&
    source.startsWith(`${quote}@`, index)
  );
}

function scanPowerShellExpandableString(
  source,
  start,
  { hereString = false } = {},
) {
  let count = 0;
  let i = start + (hereString ? 2 : 1);
  while (i < source.length) {
    if (hereString && powerShellHereTerminatorAt(source, i, '"')) {
      return { count, next: i + 2 };
    }
    if (!hereString && source[i] === '"') return { count, next: i + 1 };
    if (source[i] === "`") {
      i += 2;
    } else if (source.startsWith("$(", i)) {
      const expression = scanPowerShellCode(source, i + 2, true);
      count += expression.count;
      i = expression.next;
    } else {
      i += 1;
    }
  }
  return { count, next: source.length };
}

function scanPowerShellCode(source, start = 0, stopAtSubexpressionEnd = false) {
  let count = 0;
  let i = start;
  let parenthesisDepth = 0;
  while (i < source.length) {
    if (source.startsWith("<#", i)) {
      const end = source.indexOf("#>", i + 2);
      const stop = end === -1 ? source.length : end;
      count += markerCount(source.slice(i + 2, stop));
      i = end === -1 ? source.length : end + 2;
      continue;
    }
    if (source[i] === "#") {
      const end = source.indexOf("\n", i + 1);
      const stop = end === -1 ? source.length : end;
      count += markerCount(source.slice(i + 1, stop));
      i = stop;
      continue;
    }
    if (source.startsWith("@'", i)) {
      i = skipPowerShellHereString(source, i, "'");
      continue;
    }
    if (source.startsWith('@"', i)) {
      const string = scanPowerShellExpandableString(source, i, {
        hereString: true,
      });
      count += string.count;
      i = string.next;
      continue;
    }
    if (source[i] === "'") {
      i = skipPowerShellString(source, i, "'");
      continue;
    }
    if (source[i] === '"') {
      const string = scanPowerShellExpandableString(source, i);
      count += string.count;
      i = string.next;
      continue;
    }
    if (source.startsWith("$(", i)) {
      const expression = scanPowerShellCode(source, i + 2, true);
      count += expression.count;
      i = expression.next;
      continue;
    }
    if (source[i] === "(") {
      parenthesisDepth += 1;
      i += 1;
      continue;
    }
    if (source[i] === ")") {
      if (stopAtSubexpressionEnd && parenthesisDepth === 0)
        return { count, next: i + 1 };
      parenthesisDepth = Math.max(0, parenthesisDepth - 1);
      i += 1;
      continue;
    }
    i += 1;
  }
  return { count, next: source.length };
}

function countPowerShellComments(source) {
  return scanPowerShellCode(source).count;
}

/** Count TODO/FIXME/XXX markers that occur in source comments for a supported file type. */
export function countCommentMarkers(source, filePath) {
  switch (extname(filePath).toLowerCase()) {
    case ".java":
      return countCStyleComments(source, { javaTextBlocks: true });
    case ".js":
    case ".mjs":
    case ".cjs":
    case ".ts":
    case ".tsx":
      return countJavaScriptComments(source);
    case ".rs":
      return countCStyleComments(source, { rust: true });
    case ".py":
      return countPythonComments(source);
    case ".ps1":
      return countPowerShellComments(source);
    default:
      throw new Error(`todo-fixme: no comment extractor for '${filePath}'`);
  }
}

import assert from "node:assert/strict";

import { countCommentMarkers } from "./comment-scanner.mjs";

const cases = [
  {
    language: "Java",
    path: "A.java",
    source: `
      String plain = "TODO FIXME XXX // not comments";
      String text = """TODO /* FIXME */ XXX""";
      char slash = '/';
      // TODO one
      /* FIXME two; XXX three */
    `,
    expected: 3,
  },
  {
    language: "JavaScript",
    path: "a.mjs",
    source: `
      const plain = "TODO // FIXME";
      const template = \`XXX /* TODO */\`;
      // FIXME one
      /* TODO two; XXX three */
    `,
    expected: 3,
  },
  {
    language: "TypeScript",
    path: "a.tsx",
    source: `
      const plain: string = 'TODO /* FIXME */';
      const template = \`XXX // TODO\`;
      // TODO one
      /* FIXME two */
    `,
    expected: 2,
  },
  {
    language: "JavaScript template interpolations",
    path: "nested.mjs",
    source: [
      "const escaped = `literal TODO \\${ // FIXME is still literal }`;",
      "const outer = `literal XXX ${(() => {",
      "  // TODO interpolation one",
      '  const string = "} // FIXME string";',
      "  const regex = /[}][/]\\* XXX \\*[/]/;",
      '  const nested = `literal FIXME ${ /* FIXME interpolation two */ { value: "}" } }`;',
      "  /* XXX interpolation three with } and ${ and ` */",
      "  return nested;",
      "})()}`;",
    ].join("\n"),
    expected: 3,
  },
  {
    language: "Rust",
    path: "lib.rs",
    source: String.raw`
      let plain = "TODO // FIXME";
      let raw = r###"XXX /* TODO */"###;
      let byte_raw = br#"FIXME // TODO"#;
      let lifetime: &'static str = "not a char literal";
      let ch = '/';
      // TODO one
      /* FIXME two /* XXX three */ TODO four */
    `,
    expected: 4,
  },
  {
    language: "Python",
    path: "a.py",
    source: `
plain = "TODO # FIXME"
doc = """XXX
# TODO in a docstring
"""
raw = r'FIXME # XXX'
# TODO one; FIXME two
    `,
    expected: 2,
  },
  {
    language: "PowerShell",
    path: "a.ps1",
    source: `
$plain = "TODO # FIXME"
$literal = 'XXX # TODO'
$here = @"
FIXME # TODO
"@
# FIXME one
<# TODO two; XXX three #>
    `,
    expected: 3,
  },
  {
    language: "PowerShell expandable-string subexpressions",
    path: "nested.ps1",
    source: [
      '"$(<# TODO immediate interpolation #> $value)"',
      '$escaped = "literal TODO `$($value # FIXME is still literal)"',
      '$outer = @"',
      "literal XXX",
      '$(Get-Item ("literal TODO $(<# FIXME nested one #> $value)"))',
      '$("# XXX string"; # TODO interpolation two',
      "  $value)",
      "$(<# XXX interpolation three with ) and $( #> $value)",
      '"@',
      "$literalHere = @'",
      "$(# FIXME literal here-string)",
      "'@",
    ].join("\n"),
    expected: 4,
  },
];

for (const test of cases) {
  assert.equal(
    countCommentMarkers(test.source, test.path),
    test.expected,
    `${test.language} must count comment markers and ignore string-like text`,
  );
}

assert.throws(
  () => countCommentMarkers("// TODO", "unknown.kt"),
  /no comment extractor/,
  "new source extensions fail closed until their comment syntax is defined",
);

console.log(
  `todo-fixme comment-scanner: all ${cases.length + 1} checks passed`,
);

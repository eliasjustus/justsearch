---
classification: mirror-retirement
mirror: synonyms.de
tempdoc: 581
---

Retire the `synonyms.de` mirror. The per-language synonym list
`SSOT/catalogs/synonyms.de.v1.txt` (and its classpath copy) was deleted by the tempdoc 581 /
ADR-0043 collapse (native multilingual, no per-language levers). The file was empty (comments
only) and wired only to the never-queried `content_de` field; analysis is now locale-invariant
(ICU + NFC + lowercase). No file remains to dual-copy, so the mirror entry is removed.

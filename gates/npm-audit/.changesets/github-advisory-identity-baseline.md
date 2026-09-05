---
classification: declared-regression
tempdoc: 921
---
The provider-specific npm severity-count baseline is replaced by the current set of
high-severity GitHub Global Security Advisory identities for the same root and `ui-web`
lockfiles. This is a representation migration, not acceptance of a new dependency change:
the lockfiles are unchanged, every accepted identity is present in the migration report,
and future additions or severity escalations require their own same-change declaration and
repin.

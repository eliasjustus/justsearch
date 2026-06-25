// Self-test fixture (positive): the reflective totality guard for the FixtureTrace record. Its
// existence (a *<Name>*.java under modules/) is what the dangling-guard check resolves; the name
// matching /conformance|projection|searchtrace/i is what Check 6 requires; and its registration as
// a guardKind:"reflective" surface for recordId FixtureTrace is what Check 7 requires.
class FixtureProjectionConformanceTest {}

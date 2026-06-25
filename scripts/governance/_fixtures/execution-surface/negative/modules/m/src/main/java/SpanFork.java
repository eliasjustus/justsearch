// Self-test fixture (negative): an UNREGISTERED Java-main file emitting a search/* span-name
// literal — Check 5 (undeclared-vocabulary-fork) must flag it.
class SpanFork {
  void emit(Object tracer) {
    // tracer.spanBuilder("search/fork") — the span-name literal the vocabulary scan detects.
    String name = "search/fork";
    System.out.println(name);
  }
}

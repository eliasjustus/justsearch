// Self-test fixture (positive): a REGISTERED Java-main file emitting a search/* span-name literal —
// Check 5 passes because it is declared in the fixture register.
class SpanEmitter {
  void emit(Object tracer) {
    String name = "search/ok";
    System.out.println(name);
  }
}

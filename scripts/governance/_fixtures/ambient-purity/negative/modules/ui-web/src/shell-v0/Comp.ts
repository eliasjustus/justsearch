// Negative fixture — a component that extends raw LitElement (NOT JfElement), so
// it misses the adopted ambient sheet (574 Move 1 violation). The gate must FAIL.
export class Comp extends LitElement {}

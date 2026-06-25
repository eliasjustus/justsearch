// Negative fixture — a registered transient adopter that does NOT compose the
// controller; it hand-rolls a register/closeOthers/unregister triad that can
// drift out of single-open arbitration. The gate must FAIL.
export class Menu {
  open() {
    this.registerTransient('menu');
    this.closeOthersInLayer('menu');
  }
}

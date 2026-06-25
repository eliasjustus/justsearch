// Negative fixture — a half-wired modal: it calls .showModal() (focus-trap +
// stacking) but does NOT compose a ModalityController, so it leaks background
// scroll-lock + focus-restore (the 574 Move 4 / S9 class). The gate must FAIL.
export class Dlg {
  open(d) {
    d.showModal();
  }
}

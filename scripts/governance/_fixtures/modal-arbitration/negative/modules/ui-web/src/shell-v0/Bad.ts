// Negative fixture — a registered modal host that does NOT compose the
// ModalController; it pairs showModal()/close() with manual enter/exit calls
// that can drift (a half-wired modal). The gate must FAIL.
export class Confirm {
  open(d) {
    d.showModal();
  }
}

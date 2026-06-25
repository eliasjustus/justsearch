// Positive fixture — a modal that binds the FULL contract: it calls .showModal()
// AND composes a ModalityController (scroll-lock + focus-restore). No violation.
import { ModalityController } from '../primitives/modality.js';
export class Dlg {
  modality = new ModalityController(this);
  open(d) {
    this.modality.enter();
    d.showModal();
  }
}

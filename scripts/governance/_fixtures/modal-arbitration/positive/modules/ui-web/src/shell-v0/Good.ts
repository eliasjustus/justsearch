// Positive fixture — the registered modal host composes the one ModalController,
// so the full modal contract (showModal + scroll-lock + focus-restore) fires
// atomically by construction.
import { ModalController } from './primitives/modalController.js';
export class Confirm {
  modal = new ModalController(this);
}

// Positive fixture — the registered transient adopter composes the one
// TransientController, so single-open is arbitrated by construction.
import { TransientController } from './primitives/transientController.js';
export class Menu {
  transient = new TransientController(this, 'menu');
}

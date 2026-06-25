// Fixture FE registrations (positive) — every core shape mounts the one window.
import { ONE_WINDOW_MOUNT_TAG } from './coreInteractionShapes.js';
registerViewFactory('core.rag-ask', ONE_WINDOW_MOUNT_TAG);
registerViewFactory('core.free-chat', ONE_WINDOW_MOUNT_TAG);
registerViewFactory('core.extract', ONE_WINDOW_MOUNT_TAG);
registerViewFactory('core.agent-run', 'jf-unified-chat-view');

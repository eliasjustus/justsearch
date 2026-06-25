// SPDX-License-Identifier: Apache-2.0
/**
 * Side-effect Lumino CSS bundle for shell consumers.
 *
 * Lumino ships its layout CSS at `@lumino/widgets/style/index.css`.
 * We import it here so the shell's consumers don't need to know
 * about Lumino's internal CSS distribution. Vite resolves the CSS
 * import natively and bundles it into the application's stylesheet.
 *
 * Order matters slightly: this file is imported by `Shell.ts`, so
 * any consumer of `Shell` automatically gets the Lumino styles.
 */

import '@lumino/widgets/style/index.css';
import '../themes/lumino-theme.css';

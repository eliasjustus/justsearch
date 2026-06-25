// Positive fixture — every style value routes through its single-authority
// token, so the style-literal ratchet finds zero raw literals. (No baseline
// present in the fixture root → empty baseline → must be born clean.)
export const styles = `
  .panel {
    z-index: var(--z-overlay-1);
    transition: opacity var(--duration-2) var(--ease-standard);
    font-size: var(--font-size-md);
  }
`;

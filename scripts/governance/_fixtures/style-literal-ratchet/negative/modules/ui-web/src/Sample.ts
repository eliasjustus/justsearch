// Negative fixture — a file NOT in the baseline carrying raw style literals of
// all three ratcheted classes. With an empty (absent) fixture baseline this is
// a born-dirty regression → the gate must FAIL.
export const styles = `
  .panel {
    z-index: 9999;
    transition: opacity 120ms ease;
    font-size: 0.75rem;
  }
`;

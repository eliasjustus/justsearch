// Negative fixture — an ordinary component (NOT an atom authority) that
// hand-rolls a `.badge` base rule, a fork of the status-badge atom. NOT in the
// baseline -> born-dirty -> the gate must FAIL.
export const styles = `
  .badge {
    border-radius: 4px;
    padding: 2px 6px;
  }
`;

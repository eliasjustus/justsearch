export const TODO_FIXME_RULE_DESCRIPTIONS = {
  'todo-fixme/within-baseline': 'TODO/FIXME count is at or below baseline',
  'todo-fixme/silent-growth':
    'A file accumulated new TODO/FIXME comments without a declared changeset',
  'todo-fixme/declared-growth':
    'TODO/FIXME comment count grew; classification covers it',
  'todo-fixme/merge-import': 'TODO/FIXME growth via merge; classification supplied',
  'todo-fixme/emergency-override': 'TODO/FIXME growth permitted via emergency-override',
  'todo-fixme/rebalance-available':
    'TODO/FIXME count shrunk below baseline; ratchet may be rebalanced',
  'todo-fixme/rebalanced': 'TODO/FIXME baseline auto-updated',
  'todo-fixme/silent-baseline-shift':
    'Baseline relaxed in this PR without a declared changeset',
};

export const TODO_FIXME_RULE_DESCRIPTIONS = {
  'todo-fixme/within-baseline': 'Source-comment TODO/FIXME/XXX marker count is at or below baseline',
  'todo-fixme/silent-growth':
    'A file accumulated source-comment TODO/FIXME/XXX markers without a declared changeset',
  'todo-fixme/declared-growth':
    'Source-comment TODO/FIXME/XXX marker count grew; classification covers it',
  'todo-fixme/merge-import': 'Source-comment marker growth via merge; classification supplied',
  'todo-fixme/emergency-override': 'Source-comment marker growth permitted via emergency-override',
  'todo-fixme/rebalance-available':
    'Source-comment marker count shrunk below baseline; ratchet may be rebalanced',
  'todo-fixme/rebalanced': 'Source-comment marker baseline auto-updated',
  'todo-fixme/silent-baseline-shift':
    'Baseline relaxed in this PR without a declared changeset',
};

export const CONFIG_SURFACE_RULE_DESCRIPTIONS = {
  'config-surface/within-baseline': 'Configuration-surface metric is at or below baseline',
  'config-surface/silent-growth':
    'The runtime configuration surface grew without a declared changeset. Every knob is a decision deferred to runtime; 754 showed a one-shot cleanup without regrowth pressure just gets re-paid. Declare the growth or delete a knob.',
  'config-surface/declared-growth':
    'Configuration surface grew; classification covers it',
  'config-surface/merge-import': 'Configuration-surface growth via merge; classification supplied',
  'config-surface/emergency-override':
    'Configuration-surface growth permitted via emergency-override',
  'config-surface/rebalance-available':
    'Configuration surface shrank below baseline; ratchet may be rebalanced',
  'config-surface/silent-baseline-shift':
    'Configuration-surface baseline relaxed in this PR without a declared changeset',
  'config-surface/dead-key':
    'A setting is declared but nothing reads it — not resolved into ResolvedConfig, not read via its EnvRegistry constant, and its key string appears nowhere outside the configuration module. Wire it or delete the declaration.',
  'config-surface/dead-key-baselined':
    'A known dead setting, recorded in the dead-config baseline (not blessed — shrinking that list is the point)',
  'config-surface/unread-component':
    'A ResolvedConfig record component whose accessor is never called in production code',
  'config-surface/unread-component-baselined':
    'A known unread component, recorded in the dead-config baseline',
  'config-surface/report-malformed':
    'The runtime-config matrix report could not be parsed',
};

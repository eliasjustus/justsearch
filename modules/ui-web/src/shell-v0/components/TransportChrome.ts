// SPDX-License-Identifier: Apache-2.0
export interface TransportChromeEntry {
  readonly icon: string;
  readonly label: string;
  readonly cssClass: string;
}

const CHROME_MAP: Record<string, TransportChromeEntry> = {
  URL_BAR: { icon: '🔗', label: 'URL bar', cssClass: 'transport-url' },
  URL_DEEPLINK: { icon: '🔗', label: 'Deep link', cssClass: 'transport-url' },
  LLM_EMISSION: { icon: '✨', label: 'AI', cssClass: 'transport-ai' },
  PALETTE: { icon: '⌨', label: 'Palette', cssClass: 'transport-palette' },
  BUTTON: { icon: '🖱', label: 'Button', cssClass: 'transport-button' },
  RAIL: { icon: '◀', label: 'Rail', cssClass: 'transport-rail' },
  AGENT_LOOP: { icon: '🤖', label: 'Agent', cssClass: 'transport-agent' },
  MCP: { icon: '🔌', label: 'MCP tool', cssClass: 'transport-mcp' },
  PLUGIN_EMITTED: { icon: '🧩', label: 'Plugin', cssClass: 'transport-plugin' },
  SYSTEM_INTERNAL: { icon: '⚙', label: 'System', cssClass: 'transport-system' },
  SCHEDULED: { icon: '🕐', label: 'Scheduled', cssClass: 'transport-scheduled' },
  RULE_ENGINE: { icon: '📋', label: 'Rule', cssClass: 'transport-rule' },
};

const DEFAULT_CHROME: TransportChromeEntry = {
  icon: '❓',
  label: 'Unknown',
  cssClass: 'transport-unknown',
};

const loggedUnknown = new Set<string>();

export function transportChrome(transport: string): TransportChromeEntry {
  const entry = CHROME_MAP[transport];
  if (entry) return entry;
  if (!loggedUnknown.has(transport)) {
    loggedUnknown.add(transport);
    console.warn(
      `[dispatch-source] Unknown transport: ${transport} — using default chrome`,
    );
  }
  return { ...DEFAULT_CHROME, label: transport.toLowerCase().replace(/_/g, ' ') };
}

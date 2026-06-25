// SPDX-License-Identifier: Apache-2.0
/**
 * Structured Logger Utility
 * 
 * Provides consistent logging with:
 * - Levels: debug, info, warn, error
 * - Context tagging for filtering
 * - Timestamp and source info
 * - Conditional logging based on environment
 */

import { readBoolean, readString } from '../persistence/contract';

type LogLevel = 'debug' | 'info' | 'warn' | 'error';

interface LogEntry {
  level: LogLevel;
  context: string;
  message: string;
  data?: unknown;
  timestamp: string;
}

// Storage for recent logs (for debugging)
const LOG_BUFFER: LogEntry[] = [];
const MAX_LOG_ENTRIES = 100;

// Check if debug mode is enabled
const isDebugMode = () => {
  if (typeof window === 'undefined') return false;
  return (
    readBoolean('justsearch-debug', false) ||
    (import.meta as any).env?.DEV === true ||
    window.location.search.includes('debug=true')
  );
};

// Log level priority
const LEVEL_PRIORITY: Record<LogLevel, number> = {
  debug: 0,
  info: 1,
  warn: 2,
  error: 3,
};

// Minimum level to log (can be overridden via localStorage)
const getMinLevel = (): LogLevel => {
  const stored = readString('justsearch-log-level');
  if (stored && stored in LEVEL_PRIORITY) {
    return stored as LogLevel;
  }
  return isDebugMode() ? 'debug' : 'info';
};

// Format timestamp
const formatTime = () => {
  const now = new Date();
  return now.toISOString().substr(11, 12); // HH:mm:ss.sss
};

// Style presets for console
const LEVEL_STYLES: Record<LogLevel, string> = {
  debug: 'color: #6b7280; font-weight: normal',
  info: 'color: var(--text-chat); font-weight: normal',
  warn: 'color: var(--text-warning); font-weight: bold',
  error: 'color: var(--text-danger); font-weight: bold',
};

const CONTEXT_STYLE = 'color: var(--text-command); font-weight: bold';

// Core log function
function log(level: LogLevel, context: string, message: string, data?: unknown) {
  const minLevel = getMinLevel();
  
  // Skip if below minimum level
  if (LEVEL_PRIORITY[level] < LEVEL_PRIORITY[minLevel]) {
    return;
  }

  const timestamp = formatTime();
  const entry: LogEntry = { level, context, message, data, timestamp };

  // Add to buffer
  LOG_BUFFER.unshift(entry);
  if (LOG_BUFFER.length > MAX_LOG_ENTRIES) {
    LOG_BUFFER.pop();
  }

  // Format for console
  const prefix = `%c[${timestamp}]%c [${context}]%c`;
  const styles = [
    'color: #9ca3af',
    CONTEXT_STYLE,
    LEVEL_STYLES[level],
  ];

  const consoleMethod = level === 'debug' ? 'log' : level;
  
  if (data !== undefined) {
    console[consoleMethod](prefix, ...styles, message, data);
  } else {
    console[consoleMethod](prefix, ...styles, message);
  }
}

/**
 * Create a logger for a specific context (component/module)
 */
export function createLogger(context: string) {
  return {
    debug: (message: string, data?: unknown) => log('debug', context, message, data),
    info: (message: string, data?: unknown) => log('info', context, message, data),
    warn: (message: string, data?: unknown) => log('warn', context, message, data),
    error: (message: string, data?: unknown) => log('error', context, message, data),
  };
}

// Pre-created loggers for common contexts
export const appLog = createLogger('App');
export const searchLog = createLogger('Search');


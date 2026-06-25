// SPDX-License-Identifier: Apache-2.0
/**
 * WHATWG-compliant Server-Sent Events parser.
 *
 * This module provides a spec-correct SSE parser that handles:
 * - CRLF, LF, and CR line endings
 * - Multi-line data: field concatenation (joined with '\n')
 * - Comment lines starting with ':'
 * - Event type field (event:)
 * - Unknown fields (ignored per spec)
 * - Partial buffer boundaries (returns remainder for next call)
 *
 * References:
 * - https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream
 */

// ==================== Types ====================

/**
 * Parsed SSE event with optional event name and data.
 */
export interface SseEvent {
  /** Event type (defaults to 'message' if not specified). */
  event: string;
  /** Event data (joined multi-line data: fields). */
  data: string;
}

/**
 * Callback invoked for each complete SSE event.
 */
type SseEventCallback = (event: SseEvent) => void;

// ==================== Parser Implementation ====================

/**
 * Line ending patterns for SSE parsing.
 * Spec requires support for CRLF, LF, and CR as line terminators.
 */
const LINE_END_REGEX = /\r\n|\n|\r/;

/**
 * Splits input into lines, handling CRLF, LF, and CR.
 * Returns an array of lines without the terminators.
 */
function splitLines(input: string): string[] {
  return input.split(LINE_END_REGEX);
}

/**
 * Parses an SSE buffer and invokes the callback for each complete event.
 *
 * Per the WHATWG spec:
 * - Events are separated by blank lines
 * - Lines starting with ':' are comments (ignored)
 * - 'event:' sets the event type (defaults to 'message')
 * - 'data:' fields are concatenated with '\n' between them
 * - 'id:' and 'retry:' are spec-defined but we don't track them here
 * - Other fields are ignored
 * - Trailing '\n' is removed from data before dispatch
 *
 * @param buffer - The accumulated input buffer
 * @param onEvent - Callback for each complete event
 * @returns The unconsumed remainder of the buffer (for next chunk)
 */
export function parseSseBuffer(
  buffer: string,
  onEvent: SseEventCallback
): string {
  // Find complete events by looking for double line breaks.
  // We need to support all three line ending styles: CRLF, LF, CR.
  // Double line break can be: \r\n\r\n, \n\n, \r\r, or mixed combinations.
  // Order matters - longer patterns must come first to avoid partial matches.
  const doubleLineBreakRegex = /(?:\r\n\r\n|\n\n|\r\r|\r\n\n|\n\r\n|\r\n\r|\n\r)/;

  let remainder = buffer;
  let match: RegExpExecArray | null;

  // Process complete events (terminated by double line break)
  while ((match = doubleLineBreakRegex.exec(remainder)) !== null) {
    const eventBlock = remainder.slice(0, match.index);
    remainder = remainder.slice(match.index + match[0].length);

    if (eventBlock.trim()) {
      const event = parseEventBlock(eventBlock);
      if (event) {
        onEvent(event);
      }
    }
  }

  return remainder;
}

/**
 * Parses a single event block (text between double line breaks).
 * Returns null if the block contains no dispatchable event.
 */
function parseEventBlock(block: string): SseEvent | null {
  const lines = splitLines(block);

  let eventType = 'message'; // Default per spec
  const dataLines: string[] = [];

  for (const line of lines) {
    // Empty line (shouldn't happen within a block, but be safe)
    if (line === '') {
      continue;
    }

    // Comment line: starts with ':'
    if (line.startsWith(':')) {
      continue;
    }

    // Parse field:value
    const colonIndex = line.indexOf(':');
    let field: string;
    let value: string;

    if (colonIndex === -1) {
      // Field with no value (e.g., "data" alone)
      field = line;
      value = '';
    } else {
      field = line.slice(0, colonIndex);
      // Remove single leading space from value if present (per spec)
      value = line.slice(colonIndex + 1);
      if (value.startsWith(' ')) {
        value = value.slice(1);
      }
    }

    // Process known fields
    switch (field) {
      case 'event':
        eventType = value;
        break;
      case 'data':
        dataLines.push(value);
        break;
      case 'id':
        // Spec field - we don't track last event ID, but valid
        break;
      case 'retry':
        // Spec field - reconnection time, we don't track it
        break;
      default:
        // Unknown fields are ignored per spec
        break;
    }
  }

  // If no data was received, don't dispatch an event
  if (dataLines.length === 0) {
    return null;
  }

  // Join data lines with '\n' (per spec, each data: line appends a newline)
  const data = dataLines.join('\n');

  return { event: eventType, data };
}

/**
 * Convenience function that parses SSE and returns JSON-parsed data.
 * Returns the unconsumed remainder.
 *
 * This is a drop-in replacement for the legacy parseSSE function,
 * but with spec-correct parsing.
 */
export function parseSseBufferJson(
  buffer: string,
  onEvent: (event: string, data: Record<string, unknown>) => void
): string {
  return parseSseBuffer(buffer, (evt) => {
    try {
      const parsed = evt.data ? (JSON.parse(evt.data) as Record<string, unknown>) : {};
      onEvent(evt.event, parsed);
    } catch {
      // Malformed JSON - skip event (tolerant parsing)
    }
  });
}


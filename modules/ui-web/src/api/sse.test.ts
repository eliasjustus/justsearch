/**
 * Tests for the WHATWG-compliant SSE parser.
 */

import { describe, it, expect, vi } from 'vitest';
import { parseSseBuffer, parseSseBufferJson, type SseEvent } from './sse';

describe('SSE parser', () => {
  describe('parseSseBuffer', () => {
    it('parses a simple event with LF line endings', () => {
      const events: SseEvent[] = [];
      const remainder = parseSseBuffer(
        'event: chunk\ndata: {"text":"hello"}\n\n',
        (e) => events.push(e)
      );

      expect(events).toHaveLength(1);
      expect(events[0]).toEqual({
        event: 'chunk',
        data: '{"text":"hello"}',
      });
      expect(remainder).toBe('');
    });

    it('parses events with CRLF line endings', () => {
      const events: SseEvent[] = [];
      const remainder = parseSseBuffer(
        'event: chunk\r\ndata: {"text":"hello"}\r\n\r\n',
        (e) => events.push(e)
      );

      expect(events).toHaveLength(1);
      expect(events[0]).toEqual({
        event: 'chunk',
        data: '{"text":"hello"}',
      });
      expect(remainder).toBe('');
    });

    it('parses events with CR line endings', () => {
      const events: SseEvent[] = [];
      const remainder = parseSseBuffer(
        'event: chunk\rdata: {"text":"hello"}\r\r',
        (e) => events.push(e)
      );

      expect(events).toHaveLength(1);
      expect(events[0]).toEqual({
        event: 'chunk',
        data: '{"text":"hello"}',
      });
      expect(remainder).toBe('');
    });

    it('defaults to "message" event type when not specified', () => {
      const events: SseEvent[] = [];
      parseSseBuffer('data: test\n\n', (e) => events.push(e));

      expect(events).toHaveLength(1);
      expect(events[0]!.event).toBe('message');
      expect(events[0]!.data).toBe('test');
    });

    it('concatenates multi-line data: fields with newlines', () => {
      const events: SseEvent[] = [];
      parseSseBuffer(
        'data: line1\ndata: line2\ndata: line3\n\n',
        (e) => events.push(e)
      );

      expect(events).toHaveLength(1);
      expect(events[0]!.data).toBe('line1\nline2\nline3');
    });

    it('ignores comment lines starting with ":"', () => {
      const events: SseEvent[] = [];
      parseSseBuffer(
        ': this is a comment\nevent: test\n: another comment\ndata: value\n\n',
        (e) => events.push(e)
      );

      expect(events).toHaveLength(1);
      expect(events[0]).toEqual({
        event: 'test',
        data: 'value',
      });
    });

    it('ignores unknown fields', () => {
      const events: SseEvent[] = [];
      parseSseBuffer(
        'event: test\nunknownfield: ignored\ndata: value\n\n',
        (e) => events.push(e)
      );

      expect(events).toHaveLength(1);
      expect(events[0]).toEqual({
        event: 'test',
        data: 'value',
      });
    });

    it('handles id: and retry: fields without error', () => {
      const events: SseEvent[] = [];
      parseSseBuffer(
        'id: 123\nevent: test\nretry: 5000\ndata: value\n\n',
        (e) => events.push(e)
      );

      expect(events).toHaveLength(1);
      expect(events[0]).toEqual({
        event: 'test',
        data: 'value',
      });
    });

    it('removes single leading space from field value', () => {
      const events: SseEvent[] = [];
      parseSseBuffer('data:  two spaces\n\n', (e) => events.push(e));

      // Only first space removed per spec
      expect(events[0]!.data).toBe(' two spaces');
    });

    it('handles field with no value', () => {
      const events: SseEvent[] = [];
      parseSseBuffer('data\n\n', (e) => events.push(e));

      expect(events).toHaveLength(1);
      expect(events[0]!.data).toBe('');
    });

    it('handles field with colon but no value', () => {
      const events: SseEvent[] = [];
      parseSseBuffer('data:\n\n', (e) => events.push(e));

      expect(events).toHaveLength(1);
      expect(events[0]!.data).toBe('');
    });

    it('parses multiple events in sequence', () => {
      const events: SseEvent[] = [];
      parseSseBuffer(
        'event: chunk\ndata: {"n":1}\n\nevent: chunk\ndata: {"n":2}\n\nevent: done\ndata: {"ok":true}\n\n',
        (e) => events.push(e)
      );

      expect(events).toHaveLength(3);
      expect(events[0]).toEqual({ event: 'chunk', data: '{"n":1}' });
      expect(events[1]).toEqual({ event: 'chunk', data: '{"n":2}' });
      expect(events[2]).toEqual({ event: 'done', data: '{"ok":true}' });
    });

    it('returns unconsumed remainder for incomplete events', () => {
      const events: SseEvent[] = [];
      const remainder = parseSseBuffer(
        'event: chunk\ndata: {"n":1}\n\nevent: partial\ndata: incomp',
        (e) => events.push(e)
      );

      expect(events).toHaveLength(1);
      expect(events[0]).toEqual({ event: 'chunk', data: '{"n":1}' });
      expect(remainder).toBe('event: partial\ndata: incomp');
    });

    it('handles chunk boundary in middle of event', () => {
      const events: SseEvent[] = [];
      // First chunk
      let remainder = parseSseBuffer('event: test\ndat', (e) => events.push(e));
      expect(events).toHaveLength(0);
      expect(remainder).toBe('event: test\ndat');

      // Second chunk completes the event
      remainder = parseSseBuffer(remainder + 'a: value\n\n', (e) => events.push(e));
      expect(events).toHaveLength(1);
      expect(events[0]).toEqual({ event: 'test', data: 'value' });
      expect(remainder).toBe('');
    });

    it('handles chunk boundary in data content', () => {
      const events: SseEvent[] = [];
      // First chunk with partial JSON
      let remainder = parseSseBuffer(
        'event: chunk\ndata: {"text":"hel',
        (e) => events.push(e)
      );
      expect(events).toHaveLength(0);

      // Complete the event
      parseSseBuffer(
        remainder + 'lo world"}\n\n',
        (e) => events.push(e)
      );
      expect(events).toHaveLength(1);
      expect(events[0]!.data).toBe('{"text":"hello world"}');
    });

    it('does not dispatch event if no data: fields present', () => {
      const events: SseEvent[] = [];
      parseSseBuffer('event: test\n\n', (e) => events.push(e));

      expect(events).toHaveLength(0);
    });

    it('handles mixed line endings', () => {
      const events: SseEvent[] = [];
      // LF after event, CRLF after data, double LF to end
      parseSseBuffer('event: test\ndata: value\r\n\n', (e) => events.push(e));

      expect(events).toHaveLength(1);
      expect(events[0]).toEqual({ event: 'test', data: 'value' });
    });

    it('handles empty data lines in multi-line data', () => {
      const events: SseEvent[] = [];
      parseSseBuffer('data: line1\ndata:\ndata: line3\n\n', (e) => events.push(e));

      expect(events).toHaveLength(1);
      expect(events[0]!.data).toBe('line1\n\nline3');
    });
  });

  describe('parseSseBufferJson', () => {
    it('parses events with JSON data', () => {
      const events: { event: string; data: Record<string, unknown> }[] = [];
      parseSseBufferJson(
        'event: chunk\ndata: {"text":"hello"}\n\n',
        (event, data) => events.push({ event, data })
      );

      expect(events).toHaveLength(1);
      expect(events[0]).toEqual({
        event: 'chunk',
        data: { text: 'hello' },
      });
    });

    it('skips events with malformed JSON', () => {
      const events: { event: string; data: Record<string, unknown> }[] = [];
      const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

      parseSseBufferJson(
        'event: chunk\ndata: {invalid json}\n\nevent: chunk\ndata: {"valid":true}\n\n',
        (event, data) => events.push({ event, data })
      );

      expect(events).toHaveLength(1);
      expect(events[0]!.data).toEqual({ valid: true });

      consoleSpy.mockRestore();
    });

    it('handles empty data as empty object', () => {
      const events: { event: string; data: Record<string, unknown> }[] = [];
      parseSseBufferJson('data:\n\n', (event, data) => events.push({ event, data }));

      // Empty string is falsy, so parseSseBufferJson returns empty object {}
      expect(events).toHaveLength(1);
      expect(events[0]).toEqual({ event: 'message', data: {} });
    });

    it('handles data: with empty JSON object', () => {
      const events: { event: string; data: Record<string, unknown> }[] = [];
      parseSseBufferJson('data: {}\n\n', (event, data) => events.push({ event, data }));

      expect(events).toHaveLength(1);
      expect(events[0]!.data).toEqual({});
    });

    it('returns remainder for streaming', () => {
      const events: { event: string; data: Record<string, unknown> }[] = [];
      const remainder = parseSseBufferJson(
        'event: chunk\ndata: {"n":1}\n\ndata: partial',
        (event, data) => events.push({ event, data })
      );

      expect(events).toHaveLength(1);
      expect(remainder).toBe('data: partial');
    });
  });
});


import { describe, expect, it } from 'vitest';
import { SseParser } from './sse.js';

describe('SseParser', () => {
  it('parses complete events and ignores heartbeats', () => {
    const parser = new SseParser();
    expect(parser.push('event:ready\ndata:{"version":1}\n\n:heartbeat\n\nevent: flags-changed\ndata: {"version":2}\n\n')).toEqual([
      { event: 'ready', data: '{"version":1}' },
      { event: 'flags-changed', data: '{"version":2}' },
    ]);
  });

  it('reassembles events split across arbitrary chunk boundaries', () => {
    const parser = new SseParser();
    const raw = 'event:flags-changed\r\ndata:{"version":42}\r\n\r\n';
    const messages = [...raw].flatMap((ch) => parser.push(ch));
    expect(messages).toEqual([{ event: 'flags-changed', data: '{"version":42}' }]);
  });

  it('joins multi-line data', () => {
    expect(new SseParser().push('data:a\ndata:b\n\n')).toEqual([{ event: 'message', data: 'a\nb' }]);
  });
});

export interface SseMessage {
  event: string;
  data: string;
}

/**
 * Incremental parser for the text/event-stream format. Network chunks can split lines and
 * events anywhere, so input is buffered until a blank line terminates an event.
 * Comment lines (":heartbeat") are ignored.
 */
export class SseParser {
  private buffer = '';
  /** A trailing CR is held back: it may be the first half of a CRLF split across chunks. */
  private pendingCr = false;

  push(chunk: string): SseMessage[] {
    let text = (this.pendingCr ? '\r' : '') + chunk;
    this.pendingCr = text.endsWith('\r');
    if (this.pendingCr) text = text.slice(0, -1);
    this.buffer += text.replace(/\r\n?/g, '\n');
    const messages: SseMessage[] = [];
    let boundary: number;
    while ((boundary = this.buffer.indexOf('\n\n')) !== -1) {
      const block = this.buffer.slice(0, boundary);
      this.buffer = this.buffer.slice(boundary + 2);
      const message = parseBlock(block);
      if (message) messages.push(message);
    }
    return messages;
  }
}

function parseBlock(block: string): SseMessage | null {
  let event = 'message';
  const data: string[] = [];
  for (const line of block.split('\n')) {
    if (line === '' || line.startsWith(':')) continue;
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') event = value;
    else if (field === 'data') data.push(value);
  }
  return data.length || event !== 'message' ? { event, data: data.join('\n') } : null;
}

/**
 * SSE 增量解析。
 *
 * 这一层最容易错的地方全在边界上：一个 chunk 可能切在 `data:` 中间、
 * 切在 `\n\n` 中间，甚至把两条事件粘在同一个 chunk 里。
 * 所以解析器必须自己维护缓冲区，只把「完整的一帧」交出去。
 *
 * 规则按 SSE 规范：
 * - 帧之间用空行分隔（`\n\n`，兼容 `\r\n\r\n`）；
 * - 同一帧可有多行 `data:`，用 `\n` 拼接；
 * - 以 `:` 开头的行是注释（心跳），忽略但不报错；
 * - `event:` 行给出事件名，缺省为 message。
 */
export interface SseEvent {
  event: string;
  data: string;
}

export class SseParser {
  private buffer = "";

  /** 喂一段文本，返回已经拼完整的事件。不完整的部分留在内部缓冲。 */
  push(chunk: string): SseEvent[] {
    this.buffer += chunk;
    const events: SseEvent[] = [];

    // 统一换行，避免 \r\n 与 \n 混用时切不出帧
    this.buffer = this.buffer.replace(/\r\n/g, "\n").replace(/\r/g, "\n");

    let splitIndex = this.buffer.indexOf("\n\n");
    while (splitIndex >= 0) {
      const frame = this.buffer.slice(0, splitIndex);
      this.buffer = this.buffer.slice(splitIndex + 2);
      const event = this.parseFrame(frame);
      if (event) events.push(event);
      splitIndex = this.buffer.indexOf("\n\n");
    }
    return events;
  }

  /** 流结束时把残留内容当作最后一帧（有些厂商最后一个事件没有空行结尾）。 */
  flush(): SseEvent[] {
    const rest = this.buffer;
    this.buffer = "";
    const event = this.parseFrame(rest);
    return event ? [event] : [];
  }

  private parseFrame(frame: string): SseEvent | null {
    if (frame.trim() === "") return null;
    let event = "message";
    const dataLines: string[] = [];

    for (const rawLine of frame.split("\n")) {
      const line = rawLine.trimEnd();
      if (line === "" || line.startsWith(":")) continue;
      if (line.startsWith("event:")) {
        event = line.slice("event:".length).trim();
        continue;
      }
      if (line.startsWith("data:")) {
        // 规范要求去掉冒号后的一个空格，但不许 trim 多余内容
        let value = line.slice("data:".length);
        if (value.startsWith(" ")) value = value.slice(1);
        dataLines.push(value);
      }
      // 其它字段（id:、retry:）本项目用不到，直接跳过
    }

    if (dataLines.length === 0) return null;
    return { event, data: dataLines.join("\n") };
  }
}

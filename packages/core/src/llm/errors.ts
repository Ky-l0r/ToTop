import type { LlmFailure } from "./types";

/**
 * HTTP 失败的分类。
 *
 * 为什么必须分档：学生看到的不能是「请求失败」四个字。
 * Key 错了要去设置页换 Key，余额不足要去充值，模型名错了要改模型——
 * 三件事的处理动作完全不同，混成一句话等于什么都没说。
 */
export function classifyHttpFailure(status: number, body: string): LlmFailure {
  const detail = body.trim().slice(0, 200);

  if (status === 401 || status === 403) {
    return {
      kind: "unauthorized",
      message: "API Key 无效或没有权限，去设置里换一个",
      status,
    };
  }
  if (status === 429) {
    // 429 有两种：限流与余额耗尽，厂商靠 error.code 区分
    if (/insufficient_quota|exceeded your current quota/i.test(detail)) {
      return { kind: "insufficient_quota", message: "这个 Key 的余额用完了", status };
    }
    return { kind: "rate_limited", message: "请求太频繁，等几秒再试", status };
  }
  if (status === 404) {
    if (/model/i.test(detail)) {
      return { kind: "model_not_found", message: "模型名不对，检查设置里的模型名", status };
    }
    return { kind: "bad_base_url", message: "接口地址不对，检查设置里的 Base URL", status };
  }
  if (status >= 500) {
    return { kind: "server", message: "模型服务暂时不可用，稍后再试", status };
  }
  if (status === 400) {
    if (/model/i.test(detail)) {
      return { kind: "model_not_found", message: "模型名不对，检查设置里的模型名", status };
    }
    return { kind: "server", message: "请求被拒绝：" + abbreviate(detail), status };
  }
  return { kind: "server", message: "请求失败（HTTP " + status + "）", status };
}

/** 网络层直接抛错（断网、DNS、证书）时用这个。 */
export function networkFailure(reason: string): LlmFailure {
  return { kind: "network", message: "连不上模型服务：" + abbreviate(reason) };
}

export function abortedFailure(): LlmFailure {
  return { kind: "aborted", message: "已取消" };
}

function abbreviate(text: string): string {
  const single = text.replace(/\s+/g, " ").trim();
  return single.length > 60 ? single.slice(0, 60) + "…" : single;
}

/**
 * 本地 ID 生成。
 *
 * 不用 `crypto.randomUUID`：App 的 webview 里不保证有，而且 H5 上只在 https 上下文可用。
 * 格式是「前缀 + 时间戳 36 进制 + 4 位随机」，够短、可读、排序近似时间序。
 * 唯一性只要求"同一毫秒内不撞"，靠随机段，不靠雪花算法——这是单机单用户的库。
 */
export function createId(
  prefix: string,
  nowUtc: number,
  random: () => number = Math.random,
): string {
  const time = Math.floor(nowUtc).toString(36);
  const noise = Math.floor(random() * 36 ** 4)
    .toString(36)
    .padStart(4, "0");
  return `${prefix}_${time}${noise}`;
}

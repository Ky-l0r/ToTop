/**
 * 图片缩放规划。
 *
 * 单题图片只发一次给模型，但一次要发 1 到 3 张，单张 4000px 的原图会让请求体到十几 MB：
 * 上传慢、token 贵、低端机还容易 OOM。所以统一按长边收口。
 *
 * 为什么是纯函数：真机上第一次发现"竖着的长截图被压成一条线"，往往已经是上线之后了。
 * 把算法抽出来就能在 Node 里把横图、竖图、正方形、超小图全试一遍。
 */
export interface ImageSize {
  width: number;
  height: number;
}

export interface ResizePlan {
  width: number;
  height: number;
  /** 实际缩放比，1 表示没动。 */
  scale: number;
  resized: boolean;
}

/** 高档机 / 中档机的取图参数。 */
export const IMAGE_PROFILE_STANDARD = { maxEdge: 1600, quality: 85 } as const;

/** 低内存或移动网络时降档：识别质量略降，换来上传与内存余量。 */
export const IMAGE_PROFILE_LEAN = { maxEdge: 1280, quality: 80 } as const;

export type ImageProfile = typeof IMAGE_PROFILE_STANDARD | typeof IMAGE_PROFILE_LEAN;

/** 长边不超过 [maxEdge]，等比缩放并保证两边至少 1px。 */
export function planResize(source: ImageSize, maxEdge: number): ResizePlan {
  const width = Math.max(1, Math.round(source.width));
  const height = Math.max(1, Math.round(source.height));
  const longest = Math.max(width, height);

  if (longest <= maxEdge) {
    return { width, height, scale: 1, resized: false };
  }

  const scale = maxEdge / longest;
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
    scale,
    resized: true,
  };
}

/**
 * 选档。低内存设备优先保证「能拍完、能发出去」，而不是「像素最多」。
 *
 * 阈值取 3GB：安卓 4GB 机型实际可用常在 1.5GB 上下，留一半给 WebView 与相机。
 */
export function pickProfile(totalMemoryBytes: number): ImageProfile {
  const threeGb = 3 * 1024 * 1024 * 1024;
  return totalMemoryBytes > 0 && totalMemoryBytes < threeGb
    ? IMAGE_PROFILE_LEAN
    : IMAGE_PROFILE_STANDARD;
}

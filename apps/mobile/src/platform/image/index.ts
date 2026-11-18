import { pickProfile, planResize, sha256OfBase64, type ImageProfile } from "@totop/core";

/**
 * 图片管线：选图 → 量尺寸 → 压缩 → 读成 base64 → 算指纹 → 拼 data URL。
 *
 * 每一步都只能真机验，所以这里把错误写清楚、把降级路径留出来：
 * - 压缩失败（H5 不支持 compressImage）→ 用原图继续，不让拍题直接失败；
 * - 读文件失败 → 明确说哪一步卡住，而不是"图片处理失败"。
 *
 * 关于 base64：模型接口的 image_url 只认 http(s) 地址或 data URL，
 * 本地临时路径发过去等于发了个字符串。所以必须读成字节。
 */

interface PlusFileEntry {
  file: (ok: (file: Blob) => void, fail?: (error: unknown) => void) => void;
}

interface PlusIo {
  resolveLocalFileSystemURL: (
    url: string,
    ok: (entry: PlusFileEntry) => void,
    fail?: (error: unknown) => void,
  ) => void;
}

declare const plus: { io: PlusIo } | undefined;

/** 一道题最多几张：跨页题目通常 2 到 3 页，再多说明学生拍散了。 */
export const MAX_PAGES = 3;

export interface PreparedImage {
  /** 压缩后的本地路径，用于缩略图与预览。 */
  path: string;
  width: number;
  height: number;
  /** 是否真的压缩过。H5 上会一直是 false。 */
  compressed: boolean;
  /** 图片内容指纹，用作缓存键与去重。 */
  sha256: string;
  dataUrl: string;
}

export interface PickOptions {
  source: "camera" | "album";
  /** 还能再选几张。 */
  remaining: number;
}

/** 打开相机或相册，返回临时路径。用户取消时返回空数组，不算错误。 */
export async function pickImages(options: PickOptions): Promise<string[]> {
  if (options.remaining <= 0) return [];

  return await new Promise<string[]>((resolve, reject) => {
    uni.chooseImage({
      count: options.remaining,
      // 一律要原图：先让系统压一遍再压一遍，画质会二次损失。
      sizeType: ["original"],
      sourceType: [options.source],
      success: (result) => {
        // 单张时部分平台直接给字符串，不保证一定是数组。
        const paths = result.tempFilePaths;
        resolve(Array.isArray(paths) ? paths : paths ? [paths] : []);
      },
      fail: (error) => {
        const message = error.errMsg ?? "";
        // 用户点取消是最常见的"失败"，不该弹错误
        if (/cancel/i.test(message)) {
          resolve([]);
          return;
        }
        reject(new Error(`没能打开${options.source === "camera" ? "相机" : "相册"}：${message}`));
      },
    });
  });
}

/** 按当前设备能力选档。取不到内存信息时用标准档，不用降档惩罚所有人。 */
export function resolveProfile(): ImageProfile {
  return pickProfile(deviceMemoryBytes());
}

function deviceMemoryBytes(): number {
  try {
    const info = uni.getSystemInfoSync() as { deviceMemory?: number; memorySize?: number };
    if (typeof info.deviceMemory === "number" && info.deviceMemory > 0) {
      return info.deviceMemory * 1024 ** 3;
    }
    if (typeof info.memorySize === "number" && info.memorySize > 0) {
      return info.memorySize * 1024 ** 2;
    }
  } catch {
    // 老版本取不到内存信息，按标准档
  }
  return 0;
}

/** 把一张本地图片处理成可直接发给模型的形态。 */
export async function prepareImage(path: string, profile: ImageProfile): Promise<PreparedImage> {
  const info = await imageInfo(path);
  const plan = planResize({ width: info.width, height: info.height }, profile.maxEdge);

  let finalPath = path;
  let compressed = false;
  if (plan.resized) {
    try {
      finalPath = await compress(path, plan.width, plan.height, profile.quality);
      compressed = true;
    } catch {
      // H5 没有 compressImage。宁可发原图多花点 token，也不要让拍题在这一步断掉。
      finalPath = path;
    }
  }

  const base64 = await readBase64(finalPath);
  return {
    path: finalPath,
    width: plan.width,
    height: plan.height,
    compressed,
    sha256: sha256OfBase64(base64),
    dataUrl: `data:image/jpeg;base64,${base64}`,
  };
}

function imageInfo(path: string): Promise<{ width: number; height: number }> {
  return new Promise((resolve, reject) => {
    uni.getImageInfo({
      src: path,
      success: (info) => resolve({ width: info.width, height: info.height }),
      fail: (error) => reject(new Error(`读不出图片尺寸：${error.errMsg ?? "未知原因"}`)),
    });
  });
}

function compress(
  path: string,
  width: number,
  height: number,
  quality: number,
): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.compressImage({
      src: path,
      quality,
      // App 3.0.0+ 支持 px 字符串。用宽高两个值同时给，避免只给一边时另一边按原图算。
      width: `${width}px`,
      height: `${height}px`,
      success: (result) => resolve(result.tempFilePath),
      fail: (error) => reject(new Error(error.errMsg ?? "压缩失败")),
    });
  });
}

/** 读本地文件为 base64（不含 data URL 前缀）。App 走 plus.io，H5 走 fetch。 */
async function readBase64(path: string): Promise<string> {
  if (typeof plus !== "undefined" && plus.io) {
    return await readWithPlusIo(path);
  }
  return await readWithFetch(path);
}

function readWithPlusIo(path: string): Promise<string> {
  return new Promise((resolve, reject) => {
    plus?.io.resolveLocalFileSystemURL(
      path,
      (entry) => {
        entry.file(
          (file) => {
            const reader = new FileReader();
            reader.onload = () => resolve(stripDataUrlPrefix(String(reader.result)));
            reader.onerror = () => reject(new Error("读取图片失败"));
            reader.readAsDataURL(file);
          },
          () => reject(new Error("读取图片失败：拿不到文件")),
        );
      },
      () => reject(new Error("读取图片失败：路径不可用")),
    );
  });
}

async function readWithFetch(path: string): Promise<string> {
  const response = await fetch(path);
  if (!response.ok) throw new Error("读取图片失败：本地文件打不开");
  const buffer = await response.arrayBuffer();
  return bytesToBase64(new Uint8Array(buffer));
}

function stripDataUrlPrefix(value: string): string {
  const comma = value.indexOf(",");
  return comma >= 0 ? value.slice(comma + 1) : value;
}

const BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

function bytesToBase64(bytes: Uint8Array): string {
  let out = "";
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i] ?? 0;
    const b1 = bytes[i + 1] ?? 0;
    const b2 = bytes[i + 2] ?? 0;
    out += BASE64_ALPHABET[b0 >> 2];
    out += BASE64_ALPHABET[((b0 & 0x03) << 4) | (b1 >> 4)];
    out += i + 1 < bytes.length ? BASE64_ALPHABET[((b1 & 0x0f) << 2) | (b2 >> 6)] : "=";
    out += i + 2 < bytes.length ? BASE64_ALPHABET[b2 & 0x3f] : "=";
  }
  return out;
}

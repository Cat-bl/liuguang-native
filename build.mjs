// 「琉光（原生）」的打包脚本：node build.mjs [--install]  → out/琉光（原生） v<版本>.apk。说明见 README.md。
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { tools, build } from './apkbuild.mjs';
import { buildShaders } from './vk/shaders.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const tk = tools();
buildShaders({ SRC: HERE, ASSETS: path.join(HERE, 'assets'), NDK: tk.NDK, run: tk.run }); // Vulkan 的着色器按 src 里的原文重新生成
await build({
  SRC: HERE,
  OUT: path.join(HERE, 'build'),
  APK_DIR: path.join(HERE, 'out'),
  KS: path.join(HERE, 'release.keystore'),
  ASSETS: path.join(HERE, 'assets'),
  noCompress: ["ttf"], // 不压缩的扩展名：系统直接映射着读，不用整个解压进内存
  tk,
  install: process.argv.includes('--install'),
});

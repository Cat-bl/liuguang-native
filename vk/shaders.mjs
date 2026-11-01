// 琉光的 Vulkan 着色器（打包前由 assets.mjs 调用）：着色器的原文只有一份，就是 Java 里的字符串常量（GL 直接用它们）；
// 这里把 src/ 里每个 new G.Prog(顶点, 片元) 的两段原文解析出来（字符串常量用 + 拼的、引用别的常量的都认），
// 转成 Vulkan 的写法（ESSL 3.10：散的 uniform 收进 std140 块、sampler 编上绑定号、varying 按名字编上 location、深度从 -1～1 换成 0～1、
// 顶点着色器最后乘一个 2×2 矩阵——画到屏幕上时上下翻、按预旋转转过去；片元着色器换成高精度，见 convert 末尾），用 NDK 的 glslc 编成 SPIR-V。
// 产物都放 assets 根目录：vk_<键>.spv（键 = 运行时那段原文的 Java hashCode 十六进制 + "_" + 长度，G.Prog 按同样的算法找），
// vk.json 是每段的 uniform 块布局（名字、类型、字节偏移）、块大小、sampler 的先后（绑定号）
import fs from 'node:fs';
import path from 'node:path';

/** Java 的 String.hashCode */
function jhash(s) { let h = 0; for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0; return h; }
export const keyOf = (src) => (jhash(src) >>> 0).toString(16) + '_' + src.length;

// ---------------- 从 Java 源码里取字符串常量和 new G.Prog(...) ----------------
function unescape(s) {
  return s.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (m, c) => c[0] === 'u' && c.length === 5 ? String.fromCharCode(parseInt(c.slice(1), 16))
    : ({ n: '\n', t: '\t', r: '\r', b: '\b', f: '\f', 0: '\0' })[c] !== undefined ? ({ n: '\n', t: '\t', r: '\r', b: '\b', f: '\f', 0: '\0' })[c] : c);
}
/** 从 i 开始读一个 Java 表达式，直到顶层的 stop 里的字符：返回 [文本, 结束位置] */
function readExpr(src, i, stop) {
  let depth = 0, j = i;
  for (; j < src.length; j++) {
    const ch = src[j];
    if (ch === '/' && src[j + 1] === '/') { j = src.indexOf('\n', j); continue; }
    if (ch === '/' && src[j + 1] === '*') { j = src.indexOf('*/', j) + 1; continue; }
    if (ch === '"') { j++; while (src[j] !== '"') { if (src[j] === '\\') j++; j++; } continue; }
    if (ch === '(') depth++;
    else if (ch === ')') { if (depth === 0 && stop.includes(')')) break; depth--; }
    else if (depth === 0 && stop.includes(ch)) break;
  }
  return [src.slice(i, j), j];
}
/** 按顶层的 + 拆开：字符串字面量、常量名（Cls.NAME 或 NAME） */
function terms(expr) {
  const out = [];
  let i = 0;
  while (i < expr.length) {
    const ch = expr[i];
    if (/\s|\+/.test(ch)) { i++; continue; }
    if (ch === '/' && expr[i + 1] === '/') { const e = expr.indexOf('\n', i); i = e < 0 ? expr.length : e; continue; }
    if (ch === '/' && expr[i + 1] === '*') { i = expr.indexOf('*/', i) + 2; continue; }
    if (ch === '"') { let j = i + 1; while (expr[j] !== '"') { if (expr[j] === '\\') j++; j++; } out.push({ lit: unescape(expr.slice(i + 1, j)) }); i = j + 1; continue; }
    const m = /^[A-Za-z_][\w.]*/.exec(expr.slice(i));
    if (!m) throw new Error('看不懂的表达式：' + expr.slice(i, i + 40));
    out.push({ id: m[0] }); i += m[0].length;
  }
  return out;
}
/** src/ 下所有 .java（包名改了也找得到） */
function javaFiles(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) javaFiles(p, out); else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}
function parseJava(SRC) {
  const consts = {}, progs = [];
  for (const file of javaFiles(path.join(SRC, 'src'))) {
    const cls = path.basename(file, '.java'), src = fs.readFileSync(file, 'utf8');
    for (const m of src.matchAll(/static\s+final\s+String\s+(\w+)\s*=/g)) consts[cls + '.' + m[1]] = { cls, expr: readExpr(src, m.index + m[0].length, ';')[0] };
    for (const m of src.matchAll(/new\s+G\.Prog\s*\(/g)) {
      const [a, e1] = readExpr(src, m.index + m[0].length, ',');
      const [b] = readExpr(src, e1 + 1, ')');
      progs.push({ cls, vs: a, fs: b });
    }
  }
  const cache = {};
  const value = (cls, expr) => terms(expr).map((t) => t.lit !== undefined ? t.lit : constant(cls, t.id)).join('');
  function constant(cls, id) {
    const full = id.includes('.') ? id : cls + '.' + id;
    if (cache[full] !== undefined) return cache[full];
    const c = consts[full];
    if (!c) throw new Error('找不到常量 ' + full);
    return (cache[full] = value(c.cls, c.expr));
  }
  return { progs: progs.map((p) => ({ cls: p.cls, vs: value(p.cls, p.vs), fs: value(p.cls, p.fs) })), HEAD: constant('G', 'HEAD'), HEAD_F: constant('G', 'HEAD_F') };
}

// ---------------- GLSL ES 3.00 → Vulkan 用的 ESSL 3.10 ----------------
const STD140 = { float: [4, 4], vec2: [8, 8], vec3: [12, 16], vec4: [16, 16], mat4: [64, 16] };
/** 顶层语句：预处理行、以 ; 结尾的声明、函数定义（带 {...}） */
function statements(src) {
  const out = [];
  let cur = '', depth = 0;
  for (let i = 0; i < src.length; i++) {
    const ch = src[i];
    if (depth === 0 && cur.trim() === '' && ch === '#') { const e = src.indexOf('\n', i); const line = src.slice(i, e < 0 ? src.length : e); out.push({ pre: line }); i = e < 0 ? src.length : e; cur = ''; continue; }
    cur += ch;
    if (ch === '{') depth++;
    else if (ch === '}') { depth--; if (depth === 0) { out.push({ text: cur.trim() }); cur = ''; } }
    else if (ch === ';' && depth === 0) { out.push({ text: cur.trim(), decl: true }); cur = ''; }
  }
  if (cur.trim()) out.push({ text: cur.trim() });
  return out;
}
const PREC = /^(highp|mediump|lowp)$/;
/** "highp vec3 a, b" → { prec, type, names } */
function declOf(rest) {
  const w = rest.replace(/,/g, ' , ').split(/\s+/).filter(Boolean);
  let k = 0, prec = '';
  if (PREC.test(w[k])) prec = w[k++];
  const type = w[k++];
  const names = w.slice(k).filter((x) => x !== ',');
  names.forEach((n) => { if (!/^\w+$/.test(n)) throw new Error('不支持的声明：' + rest); });
  return { prec, type, names };
}

function convert(src, stage, loc) {
  const st = statements(src);
  const pre = [], prec = [], members = [], samplers = [], io = [], rest = [];
  for (const s of st) {
    if (s.pre !== undefined) { if (!/^#version/.test(s.pre)) pre.push(s.pre); continue; }
    const t = s.text.replace(/;$/, '').trim();
    if (!s.decl) { rest.push(stage === 'vert' ? s.text.replace(/\bvoid\s+main\s*\(/, 'void main_(') : s.text); continue; }
    if (/^precision\b/.test(t)) { prec.push(t + ';'); continue; }
    let m;
    if ((m = /^uniform\s+(.*)$/s.exec(t))) {
      const d = declOf(m[1]);
      if (d.type === 'sampler2D') d.names.forEach((n) => samplers.push({ n, prec: d.prec }));
      else { if (!STD140[d.type]) throw new Error('不支持的 uniform 类型：' + t); d.names.forEach((n) => members.push({ n, type: d.type, prec: d.prec })); }
      continue;
    }
    if ((m = /^(layout\s*\([^)]*\)\s*)?((?:(?:flat|smooth|centroid)\s+)*)(in|out)\s+(.*)$/s.exec(t))) {
      const [, lay, interp, dir, body] = m;
      if (stage === 'vert' && dir === 'in') { if (!lay) throw new Error('顶点属性没写 location：' + t); io.push(t + ';'); continue; }
      if (stage === 'frag' && dir === 'out') { io.push(`layout(location = 0) ${interp}out ${body};`); continue; }
      const d = declOf(body);
      for (const n of d.names) {
        if (loc[n] === undefined) { if (stage === 'frag') throw new Error('片元着色器的输入 ' + n + ' 没有顶点着色器输出'); loc[n] = Object.keys(loc).length; }
        io.push(`layout(location = ${loc[n]}) ${interp}${dir} ${d.prec ? d.prec + ' ' : ''}${d.type} ${n};`);
      }
      continue;
    }
    rest.push(s.text);
  }
  // std140 布局
  let off = 0;
  const u = members.map((x) => { const [sz, al] = STD140[x.type]; off = Math.ceil(off / al) * al; const r = [x.n, x.type, off]; off += sz; return r; });
  const size = Math.ceil(off / 16) * 16;
  const out = ['#version 310 es', ...pre, ...prec];
  if (members.length) out.push(`layout(std140, set = 0, binding = ${stage === 'vert' ? 0 : 1}) uniform ${stage === 'vert' ? 'VU_' : 'FU_'} {\n` + members.map((x) => `  ${x.prec ? x.prec + ' ' : ''}${x.type} ${x.n};`).join('\n') + '\n};');
  samplers.forEach((x, i) => out.push(`layout(set = 1, binding = ${i}) uniform ${x.prec ? x.prec + ' ' : ''}sampler2D ${x.n};`));
  out.push(...io);
  if (stage === 'vert') out.push('layout(push_constant) uniform PC_ { highp vec4 m; } pc_;');
  out.push(...rest);
  if (stage === 'vert') out.push('void main() {\n  main_();\n  gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n'
    + '  gl_Position.xy = vec2(pc_.m.x * gl_Position.x + pc_.m.y * gl_Position.y, pc_.m.z * gl_Position.x + pc_.m.w * gl_Position.y);\n}');
  if (samplers.length > 4) throw new Error('一段着色器最多 4 个 sampler');
  if (size > 1024) throw new Error('uniform 块超过 1024 字节');
  let glsl = out.join('\n') + '\n';
  // 片元着色器一律按高精度：这台车（Adreno 610，驱动 512.415）的 Vulkan 编译器碰到中精度常常建不出管线（VK_INCOMPLETE），全高精度的都能建
  if (stage === 'frag') glsl = glsl.replace(/\b(mediump|lowp)\b/g, 'highp');  return { glsl, info: { s: size, u, t: samplers.map((x) => x.n) } };
}

/** 全部转好、编好放进 ASSETS（先删掉 ASSETS 里旧的 vk_*.spv、vk.json）；转好的 GLSL 先放 TMP（默认 ASSETS 旁边的 vk-glsl），编完删掉；返回段数 */
export function buildShaders({ SRC, ASSETS, NDK, run, TMP }) {
  if (!NDK) { console.error('找不到 NDK（Vulkan 的着色器要用它带的 glslc 编）：设 ANDROID_NDK_HOME，或装在 ANDROID_HOME/ndk 下'); process.exit(1); }
  const tag = process.platform === 'win32' ? 'windows-x86_64' : process.platform === 'darwin' ? 'darwin-x86_64' : 'linux-x86_64';
  const glslc = path.join(NDK, 'shader-tools', tag, process.platform === 'win32' ? 'glslc.exe' : 'glslc');
  if (!fs.existsSync(glslc)) { console.error('找不到 glslc：' + glslc); process.exit(1); }
  const { progs, HEAD, HEAD_F } = parseJava(SRC);
  for (const f of fs.readdirSync(ASSETS)) if (/^vk_.*\.spv$|^vk\.json$/.test(f)) fs.rmSync(path.join(ASSETS, f));
  const tmp = TMP || path.join(ASSETS, '..', 'vk-glsl');
  fs.rmSync(tmp, { recursive: true, force: true });
  fs.mkdirSync(tmp, { recursive: true });
  const loc = {}, info = {}, stages = [];
  // 先过所有顶点着色器（varying 的 location 由它们定），再过片元着色器
  for (const [stage, head, pick] of [['vert', HEAD, (p) => p.vs], ['frag', HEAD_F, (p) => p.fs]]) {
    for (const p of progs) {
      const src = head + pick(p), key = keyOf(src);
      if (info[key]) continue;
      let r;
      try { r = convert(src, stage, loc); } catch (e) { throw new Error(`${p.cls} 的着色器转不过来：${e.message}\n${src}`); }
      info[key] = r.info;
      const f = path.join(tmp, key + '.' + stage);
      fs.writeFileSync(f, r.glsl);
      stages.push({ key, f, stage });
    }
  }
  if (Object.keys(loc).length > 16) throw new Error('varying 太多：' + Object.keys(loc).join(', '));
  for (const s of stages) run(glslc, ['-O', '--target-env=vulkan1.0', '-fshader-stage=' + s.stage, '-o', path.join(ASSETS, 'vk_' + s.key + '.spv'), s.f]);
  fs.writeFileSync(path.join(ASSETS, 'vk.json'), JSON.stringify(info));
  fs.rmSync(tmp, { recursive: true, force: true });
  return stages.length;
}

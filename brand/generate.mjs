#!/usr/bin/env node
/* SPDX-License-Identifier: Apache-2.0 */
/**
 * Renders every derived JustSearch identity asset from the SVGs in this directory.
 *
 *     node brand/generate.mjs
 *
 * The SVGs are the authority; everything this script writes is derived and may be deleted and
 * regenerated at will. No surface invents brand -- if a surface needs the mark, it takes it from
 * here or gets a new output added here.
 *
 * WHY IT IS SELF-CONTAINED: sharp / resvg / ImageMagick / Python are all absent from at least one
 * machine that has to be able to run this (CI containers, a fresh worktree with no npm install).
 * The mark is a handful of straight edges, four quarter-arcs and two rectangles, and the wordmark
 * is pre-outlined by brand/extract-wordmark.py -- so a ~200-line scanline rasteriser plus the PNG,
 * ICO, ICNS and BMP container formats (all trivially writable) removes every dependency. Node's
 * built-in zlib does the only non-trivial part. Output is byte-deterministic.
 *
 * SIZE LADDER (the mark spec, enforced by which source file each output reads):
 *   >= 48px  master WITH footnote        mark-{light,dark}.svg
 *   25-47px  master WITHOUT footnote     mark-{light,dark}.svg, #footnote dropped
 *   <= 24px  the dedicated cut           mark-small-{light,dark}.svg (16px, 32px pixel-exact)
 *      24px  hand-snapped raster frame   mark-24-{light,dark}.svg  (see that file for the snap)
 *
 * The .ico / .icns / app PNGs all take the DARK-ground colorway: an OS icon cannot follow the OS
 * theme, and the chrome it lives in (taskbar, Start, title bar) is dark by default.
 */
import { deflateSync } from 'node:zlib';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const BRAND_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(BRAND_DIR, '..');

// ---------------------------------------------------------------------------------------------
// SVG subset parser. Handles exactly what this directory's files contain: <path d> and <rect>,
// each optionally carrying id/fill. Anything it does not understand is a hard error rather than a
// silent omission -- a silently dropped shape would ship a mark with a missing limb.
// ---------------------------------------------------------------------------------------------

function parseColor(text) {
  const hex = /^#([0-9a-f]{6})$/i.exec(text.trim());
  if (hex === null) throw new Error(`unsupported fill "${text}" (only #rrggbb)`);
  const value = Number.parseInt(hex[1], 16);
  return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
}

function attrs(tag) {
  const out = {};
  for (const match of tag.matchAll(/([\w:-]+)\s*=\s*"([^"]*)"/g)) out[match[1]] = match[2];
  return out;
}

/** Endpoint -> centre parameterisation (SVG spec F.6.5), then flatten to line segments. */
function arcToPoints(x0, y0, rx, ry, phiDeg, largeArc, sweep, x1, y1) {
  const phi = (phiDeg * Math.PI) / 180;
  const cos = Math.cos(phi);
  const sin = Math.sin(phi);
  const dx2 = (x0 - x1) / 2;
  const dy2 = (y0 - y1) / 2;
  const x1p = cos * dx2 + sin * dy2;
  const y1p = -sin * dx2 + cos * dy2;
  let rxa = Math.abs(rx);
  let rya = Math.abs(ry);
  const lambda = (x1p * x1p) / (rxa * rxa) + (y1p * y1p) / (rya * rya);
  if (lambda > 1) {
    const s = Math.sqrt(lambda);
    rxa *= s;
    rya *= s;
  }
  const sign = largeArc === sweep ? -1 : 1;
  const num = rxa * rxa * rya * rya - rxa * rxa * y1p * y1p - rya * rya * x1p * x1p;
  const den = rxa * rxa * y1p * y1p + rya * rya * x1p * x1p;
  const coef = sign * Math.sqrt(Math.max(0, num / den));
  const cxp = (coef * rxa * y1p) / rya;
  const cyp = (-coef * rya * x1p) / rxa;
  const cx = cos * cxp - sin * cyp + (x0 + x1) / 2;
  const cy = sin * cxp + cos * cyp + (y0 + y1) / 2;
  const angle = (ux, uy, vx, vy) => {
    const dot = (ux * vx + uy * vy) / (Math.hypot(ux, uy) * Math.hypot(vx, vy));
    const a = Math.acos(Math.min(1, Math.max(-1, dot)));
    return ux * vy - uy * vx < 0 ? -a : a;
  };
  const theta = angle(1, 0, (x1p - cxp) / rxa, (y1p - cyp) / rya);
  let delta = angle(
    (x1p - cxp) / rxa,
    (y1p - cyp) / rya,
    (-x1p - cxp) / rxa,
    (-y1p - cyp) / rya,
  );
  if (!sweep && delta > 0) delta -= 2 * Math.PI;
  if (sweep && delta < 0) delta += 2 * Math.PI;
  // 64 segments per full turn is far below one device pixel of chord error at every size shipped.
  const steps = Math.max(4, Math.ceil((Math.abs(delta) / (2 * Math.PI)) * 64));
  const points = [];
  for (let i = 1; i <= steps; i += 1) {
    const t = theta + (delta * i) / steps;
    points.push([
      cos * rxa * Math.cos(t) - sin * rya * Math.sin(t) + cx,
      sin * rxa * Math.cos(t) + cos * rya * Math.sin(t) + cy,
    ]);
  }
  return points;
}

const CURVE_STEPS = 24;

function quadPoints(x0, y0, cx, cy, x1, y1) {
  const points = [];
  for (let i = 1; i <= CURVE_STEPS; i += 1) {
    const t = i / CURVE_STEPS;
    const u = 1 - t;
    points.push([
      u * u * x0 + 2 * u * t * cx + t * t * x1,
      u * u * y0 + 2 * u * t * cy + t * t * y1,
    ]);
  }
  return points;
}

function cubicPoints(x0, y0, c1x, c1y, c2x, c2y, x1, y1) {
  const points = [];
  for (let i = 1; i <= CURVE_STEPS; i += 1) {
    const t = i / CURVE_STEPS;
    const u = 1 - t;
    points.push([
      u * u * u * x0 + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * x1,
      u * u * u * y0 + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * y1,
    ]);
  }
  return points;
}

/** Parse a path `d` attribute into closed contours (arrays of [x, y]). */
export function parsePath(d) {
  const tokens = d.match(/[MmLlHhVvCcSsQqTtAaZz]|-?\d*\.?\d+(?:e[-+]?\d+)?/gi) ?? [];
  const contours = [];
  let current = null;
  let x = 0;
  let y = 0;
  let startX = 0;
  let startY = 0;
  let lastCtrl = null;
  let command = '';
  let i = 0;
  const num = () => Number.parseFloat(tokens[i++]);
  const push = (points) => {
    for (const point of points) current.push(point);
    const last = points[points.length - 1];
    x = last[0];
    y = last[1];
  };
  while (i < tokens.length) {
    if (/[MmLlHhVvCcSsQqTtAaZz]/.test(tokens[i])) command = tokens[i++];
    const rel = command === command.toLowerCase();
    const c = command.toUpperCase();
    if (c === 'Z') {
      if (current !== null && current.length > 0) contours.push(current);
      current = null;
      x = startX;
      y = startY;
      lastCtrl = null;
      continue;
    }
    if (c === 'M') {
      if (current !== null && current.length > 0) contours.push(current);
      const nx = num();
      const ny = num();
      x = rel ? x + nx : nx;
      y = rel ? y + ny : ny;
      startX = x;
      startY = y;
      current = [[x, y]];
      lastCtrl = null;
      // A repeated coordinate pair after M is an implicit L.
      command = rel ? 'l' : 'L';
      continue;
    }
    if (current === null) throw new Error(`path command ${c} before any moveto`);
    if (c === 'L') {
      const nx = num();
      const ny = num();
      push([[rel ? x + nx : nx, rel ? y + ny : ny]]);
      lastCtrl = null;
    } else if (c === 'H') {
      const nx = num();
      push([[rel ? x + nx : nx, y]]);
      lastCtrl = null;
    } else if (c === 'V') {
      const ny = num();
      push([[x, rel ? y + ny : ny]]);
      lastCtrl = null;
    } else if (c === 'C' || c === 'S') {
      let c1x;
      let c1y;
      if (c === 'C') {
        c1x = rel ? x + num() : num();
        c1y = rel ? y + num() : num();
      } else {
        c1x = lastCtrl === null ? x : 2 * x - lastCtrl[0];
        c1y = lastCtrl === null ? y : 2 * y - lastCtrl[1];
      }
      const c2x = rel ? x + num() : num();
      const c2y = rel ? y + num() : num();
      const ex = rel ? x + num() : num();
      const ey = rel ? y + num() : num();
      const x0 = x;
      const y0 = y;
      push(cubicPoints(x0, y0, c1x, c1y, c2x, c2y, ex, ey));
      lastCtrl = [c2x, c2y];
    } else if (c === 'Q' || c === 'T') {
      let cx;
      let cy;
      if (c === 'Q') {
        cx = rel ? x + num() : num();
        cy = rel ? y + num() : num();
      } else {
        cx = lastCtrl === null ? x : 2 * x - lastCtrl[0];
        cy = lastCtrl === null ? y : 2 * y - lastCtrl[1];
      }
      const ex = rel ? x + num() : num();
      const ey = rel ? y + num() : num();
      const x0 = x;
      const y0 = y;
      push(quadPoints(x0, y0, cx, cy, ex, ey));
      lastCtrl = [cx, cy];
    } else if (c === 'A') {
      const rx = num();
      const ry = num();
      const rot = num();
      const large = num() !== 0;
      const sweep = num() !== 0;
      const ex = rel ? x + num() : num();
      const ey = rel ? y + num() : num();
      push(arcToPoints(x, y, rx, ry, rot, large, sweep, ex, ey));
      lastCtrl = null;
    } else {
      throw new Error(`unsupported path command "${command}"`);
    }
  }
  if (current !== null && current.length > 0) contours.push(current);
  return contours;
}

export function parseSvg(source) {
  const root = /<svg\b([^>]*)>/.exec(source);
  if (root === null) throw new Error('no <svg> element');
  const rootAttrs = attrs(root[1]);
  const [vx, vy, vw, vh] = (rootAttrs.viewBox ?? '0 0 1 1').trim().split(/\s+/).map(Number);
  const shapes = [];
  for (const match of source.matchAll(/<(path|rect)\b([^>]*?)\/?>/g)) {
    const a = attrs(match[2]);
    const fill = a.fill === undefined ? null : parseColor(a.fill);
    if (match[1] === 'path') {
      shapes.push({ id: a.id ?? '', fill, contours: parsePath(a.d) });
    } else {
      const x = Number(a.x);
      const y = Number(a.y);
      const w = Number(a.width);
      const h = Number(a.height);
      shapes.push({
        id: a.id ?? '',
        fill,
        contours: [[[x, y], [x + w, y], [x + w, y + h], [x, y + h]]],
      });
    }
  }
  if (shapes.length === 0) throw new Error('no <path>/<rect> shapes found');
  return { viewBox: { x: vx, y: vy, w: vw, h: vh }, attrs: rootAttrs, shapes };
}

// ---------------------------------------------------------------------------------------------
// Scanline rasteriser. 16 sub-scanlines per pixel row, exact horizontal span coverage, nonzero
// winding -- which is what both SVG's default fill-rule and TrueType outlines use.
// ---------------------------------------------------------------------------------------------

const SUBSAMPLES = 16;

function coverage(contours, width, height) {
  const cov = new Float64Array(width * height);
  const edges = [];
  let minY = Infinity;
  let maxY = -Infinity;
  for (const contour of contours) {
    for (let i = 0; i < contour.length; i += 1) {
      const [x0, y0] = contour[i];
      const [x1, y1] = contour[(i + 1) % contour.length];
      if (y0 === y1) continue;
      edges.push([x0, y0, x1, y1]);
      minY = Math.min(minY, y0, y1);
      maxY = Math.max(maxY, y0, y1);
    }
  }
  if (edges.length === 0) return cov;
  const yStart = Math.max(0, Math.floor(minY));
  const yEnd = Math.min(height, Math.ceil(maxY));
  const weight = 1 / SUBSAMPLES;
  const crossings = [];
  for (let py = yStart; py < yEnd; py += 1) {
    const row = py * width;
    for (let s = 0; s < SUBSAMPLES; s += 1) {
      const sy = py + (s + 0.5) / SUBSAMPLES;
      crossings.length = 0;
      for (const [x0, y0, x1, y1] of edges) {
        const lo = Math.min(y0, y1);
        const hi = Math.max(y0, y1);
        if (sy < lo || sy >= hi) continue;
        crossings.push({
          x: x0 + ((sy - y0) * (x1 - x0)) / (y1 - y0),
          dir: y1 > y0 ? 1 : -1,
        });
      }
      if (crossings.length === 0) continue;
      crossings.sort((a, b) => a.x - b.x);
      let winding = 0;
      let spanStart = 0;
      for (const crossing of crossings) {
        const before = winding;
        winding += crossing.dir;
        if (before === 0 && winding !== 0) {
          spanStart = crossing.x;
        } else if (before !== 0 && winding === 0) {
          const xa = Math.max(0, spanStart);
          const xb = Math.min(width, crossing.x);
          if (xb <= xa) continue;
          const first = Math.floor(xa);
          const last = Math.min(width - 1, Math.ceil(xb) - 1);
          for (let px = first; px <= last; px += 1) {
            const overlap = Math.min(xb, px + 1) - Math.max(xa, px);
            if (overlap > 0) cov[row + px] += overlap * weight;
          }
        }
      }
    }
  }
  return cov;
}

/** RGBA canvas holding PREMULTIPLIED float channels; converted to 8-bit straight alpha on read. */
class Canvas {
  constructor(width, height, background = null) {
    this.width = width;
    this.height = height;
    this.data = new Float64Array(width * height * 4);
    if (background !== null) this.fill(background);
  }

  fill([r, g, b]) {
    for (let i = 0; i < this.width * this.height; i += 1) {
      this.data[i * 4] = r / 255;
      this.data[i * 4 + 1] = g / 255;
      this.data[i * 4 + 2] = b / 255;
      this.data[i * 4 + 3] = 1;
    }
  }

  /** Paint shapes through an affine transform {sx, sy, tx, ty}; `color` overrides shape fills. */
  paint(shapes, { sx, sy, tx, ty }, { drop = [], color = null } = {}) {
    for (const shape of shapes) {
      if (drop.includes(shape.id)) continue;
      const rgb = color ?? shape.fill;
      if (rgb === null) throw new Error(`shape "${shape.id}" has no fill and no override`);
      const contours = shape.contours.map((contour) =>
        contour.map(([x, y]) => [x * sx + tx, y * sy + ty]),
      );
      const cov = coverage(contours, this.width, this.height);
      const [r, g, b] = [rgb[0] / 255, rgb[1] / 255, rgb[2] / 255];
      for (let i = 0; i < cov.length; i += 1) {
        const a = Math.min(1, cov[i]);
        if (a <= 0) continue;
        const o = i * 4;
        const inv = 1 - a;
        this.data[o] = r * a + this.data[o] * inv;
        this.data[o + 1] = g * a + this.data[o + 1] * inv;
        this.data[o + 2] = b * a + this.data[o + 2] * inv;
        this.data[o + 3] = a + this.data[o + 3] * inv;
      }
    }
    return this;
  }

  /** 8-bit straight-alpha RGBA. */
  rgba() {
    const out = Buffer.allocUnsafe(this.width * this.height * 4);
    for (let i = 0; i < this.width * this.height; i += 1) {
      const a = this.data[i * 4 + 3];
      const byte = (v) => Math.max(0, Math.min(255, Math.round(v * 255)));
      if (a <= 0) {
        out.writeUInt32LE(0, i * 4);
        continue;
      }
      out[i * 4] = byte(this.data[i * 4] / a);
      out[i * 4 + 1] = byte(this.data[i * 4 + 1] / a);
      out[i * 4 + 2] = byte(this.data[i * 4 + 2] / a);
      out[i * 4 + 3] = byte(a);
    }
    return out;
  }
}

// ---------------------------------------------------------------------------------------------
// Container formats. Each is small enough to write correctly by hand; the alternative is a native
// dependency that does not install on every machine that has to run this.
// ---------------------------------------------------------------------------------------------

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buffer) {
  let c = -1;
  for (const byte of buffer) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

export function encodePng(rgba, width, height) {
  const chunk = (type, data) => {
    const head = Buffer.alloc(4);
    head.writeUInt32BE(data.length, 0);
    const body = Buffer.concat([Buffer.from(type, 'latin1'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body), 0);
    return Buffer.concat([head, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // colour type: truecolour with alpha
  const raw = Buffer.allocUnsafe(height * (width * 4 + 1));
  for (let y = 0; y < height; y += 1) {
    raw[y * (width * 4 + 1)] = 0; // filter: none (keeps the output byte-stable)
    rgba.copy(raw, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** A 32bpp BMP DIB icon image: BITMAPINFOHEADER + bottom-up BGRA + 1bpp AND mask. */
function icoDib(rgba, width, height) {
  const maskStride = Math.ceil(width / 8 / 4) * 4;
  const header = Buffer.alloc(40);
  header.writeUInt32LE(40, 0);
  header.writeInt32LE(width, 4);
  header.writeInt32LE(height * 2, 8); // XOR + AND stacked, per the ICO convention
  header.writeUInt16LE(1, 12);
  header.writeUInt16LE(32, 14);
  header.writeUInt32LE(0, 16); // BI_RGB
  header.writeUInt32LE(width * height * 4 + maskStride * height, 20);
  const xor = Buffer.alloc(width * height * 4);
  const and = Buffer.alloc(maskStride * height);
  for (let y = 0; y < height; y += 1) {
    const src = y * width * 4;
    const dst = (height - 1 - y) * width * 4;
    const maskRow = (height - 1 - y) * maskStride;
    for (let x = 0; x < width; x += 1) {
      xor[dst + x * 4] = rgba[src + x * 4 + 2];
      xor[dst + x * 4 + 1] = rgba[src + x * 4 + 1];
      xor[dst + x * 4 + 2] = rgba[src + x * 4];
      xor[dst + x * 4 + 3] = rgba[src + x * 4 + 3];
      // Fully transparent pixels also set the legacy AND mask, so shells that ignore the alpha
      // channel still punch them out instead of painting them black.
      if (rgba[src + x * 4 + 3] === 0) and[maskRow + (x >> 3)] |= 0x80 >> (x & 7);
    }
  }
  return Buffer.concat([header, xor, and]);
}

export function encodeIco(frames) {
  const dir = Buffer.alloc(6);
  dir.writeUInt16LE(0, 0);
  dir.writeUInt16LE(1, 2); // 1 = icon
  dir.writeUInt16LE(frames.length, 4);
  const entries = [];
  const blobs = [];
  let offset = 6 + frames.length * 16;
  for (const frame of frames) {
    // Modern convention: PNG payload at 256 (a raw DIB there costs 256 KB), DIB below it, where
    // some shell paths still refuse PNG.
    const blob =
      frame.size >= 256
        ? encodePng(frame.rgba, frame.size, frame.size)
        : icoDib(frame.rgba, frame.size, frame.size);
    const entry = Buffer.alloc(16);
    entry[0] = frame.size >= 256 ? 0 : frame.size; // 0 means 256
    entry[1] = frame.size >= 256 ? 0 : frame.size;
    entry[2] = 0; // palette size
    entry[3] = 0; // reserved
    entry.writeUInt16LE(1, 4); // colour planes
    entry.writeUInt16LE(32, 6); // bits per pixel
    entry.writeUInt32LE(blob.length, 8);
    entry.writeUInt32LE(offset, 12);
    offset += blob.length;
    entries.push(entry);
    blobs.push(blob);
  }
  return Buffer.concat([dir, ...entries, ...blobs]);
}

/**
 * ICNS with PNG payloads (supported since OS X 10.7, and what `iconutil` itself emits). macOS is
 * not a shipped target today -- `bundle.targets` is nsis-only -- but `bundle.icon` lists the file
 * and tauri-bundler canonicalises every entry at build time, so it has to exist and be real.
 */
export function encodeIcns(entries) {
  const chunks = entries.map(([type, png]) => {
    const head = Buffer.alloc(8);
    head.write(type, 0, 'latin1');
    head.writeUInt32BE(png.length + 8, 4);
    return Buffer.concat([head, png]);
  });
  const body = Buffer.concat(chunks);
  const head = Buffer.alloc(8);
  head.write('icns', 0, 'latin1');
  head.writeUInt32BE(body.length + 8, 4);
  return Buffer.concat([head, body]);
}

/** 24-bit uncompressed BMP -- the only format NSIS/MUI accepts for its wizard bitmaps. */
export function encodeBmp24(rgba, width, height) {
  const stride = Math.ceil((width * 3) / 4) * 4;
  const pixels = Buffer.alloc(stride * height);
  for (let y = 0; y < height; y += 1) {
    const src = y * width * 4;
    const dst = (height - 1 - y) * stride; // BMP rows run bottom-up
    for (let x = 0; x < width; x += 1) {
      pixels[dst + x * 3] = rgba[src + x * 4 + 2];
      pixels[dst + x * 3 + 1] = rgba[src + x * 4 + 1];
      pixels[dst + x * 3 + 2] = rgba[src + x * 4];
    }
  }
  const file = Buffer.alloc(14);
  file.write('BM', 0, 'latin1');
  file.writeUInt32LE(54 + pixels.length, 2);
  file.writeUInt32LE(54, 10);
  const info = Buffer.alloc(40);
  info.writeUInt32LE(40, 0);
  info.writeInt32LE(width, 4);
  info.writeInt32LE(height, 8);
  info.writeUInt16LE(1, 12);
  info.writeUInt16LE(24, 14);
  info.writeUInt32LE(0, 16);
  info.writeUInt32LE(pixels.length, 20);
  info.writeInt32LE(2835, 24); // 72 dpi
  info.writeInt32LE(2835, 28);
  return Buffer.concat([file, info, pixels]);
}

// ---------------------------------------------------------------------------------------------
// Outputs
// ---------------------------------------------------------------------------------------------

const svg = (name) => parseSvg(readFileSync(join(BRAND_DIR, name), 'utf8'));

/** The size ladder as a function: pick the source file and shape set for a RENDERED mark size. */
function markSource(renderedSize, colorway) {
  if (renderedSize <= 16) return { doc: svg(`mark-small-${colorway}.svg`), drop: [] };
  if (renderedSize === 24) return { doc: svg(`mark-24-${colorway}.svg`), drop: [] };
  if (renderedSize < 25) return { doc: svg(`mark-small-${colorway}.svg`), drop: [] };
  if (renderedSize < 48) return { doc: svg(`mark-${colorway}.svg`), drop: ['footnote'] };
  return { doc: svg(`mark-${colorway}.svg`), drop: [] };
}

function paintMark(canvas, { doc, drop }, { x, y, size }) {
  const scale = size / doc.viewBox.w;
  canvas.paint(doc.shapes, {
    sx: scale,
    sy: scale,
    tx: x - doc.viewBox.x * scale,
    ty: y - doc.viewBox.y * scale,
  }, { drop });
}

const PLATE_INK = [0x0e, 0x0f, 0x12];

/** A rounded rectangle as one flattened contour, y-down. */
export function roundedRect(x, y, w, h, r) {
  const points = [];
  const corner = (cx, cy, from, to) => {
    for (let i = 0; i <= 24; i += 1) {
      const t = from + ((to - from) * i) / 24;
      points.push([cx + r * Math.cos(t), cy + r * Math.sin(t)]);
    }
  };
  corner(x + r, y + r, Math.PI, Math.PI * 1.5);
  corner(x + w - r, y + r, Math.PI * 1.5, Math.PI * 2);
  corner(x + w - r, y + h - r, 0, Math.PI * 0.5);
  corner(x + r, y + h - r, Math.PI * 0.5, Math.PI);
  return points;
}

/**
 * ICON-CLASS geometry: the mark on a near-black rounded plate.
 *
 * WHY THE PLATE EXISTS, and why it is not the thing the mark's self-critique refused. The mark is
 * specified on a near-white OR near-black ground; a mid-tone ground collapses it, and the dark
 * colorway over white is nearly invisible because the slot is cut THROUGH the mass. Every other
 * surface supplies a compliant ground of its own -- the installer bitmaps, the app UI, a document
 * page. An OS icon cannot: Windows draws it over wallpaper, light Explorer panes, dark taskbars,
 * whatever. So the icon PACKAGES its specified ground with it. That is supplying-the-ground, not
 * the glyph-in-rounded-app-square identity formula: the mark itself stays plateless everywhere a
 * ground already exists, and the plate is not part of the mark.
 *
 * Orchestrator call under the autonomous directive, 2026-08-06; owner-overridable (tempdoc 815 §7).
 *
 * Geometry: inset ~1/16 of the frame, corner radius ~1/8, both snapped to whole pixels.
 *   - At 16px the inset is 0 (full bleed). A 1px inset costs 12.5% of the cell AND its 2px radius
 *     cuts the corners off the <=24px cut's mass, whose left wall sits at x=1: the corner arc
 *     centred on (3,3) leaves (1,2) and (1,14) outside the plate. Full bleed is also the standard
 *     16px treatment.
 *   - At and below 24px the mark is drawn 1:1 over the FULL frame so the hand-snapped pixel grid
 *     survives, and the plate is sized to contain it (at 24px: inset 1, radius 3 -- the mark's
 *     corners (2,3) and (2,21) sit 2.24 from the arc centres (4,4)/(4,20), inside r=3).
 *   - At 25px and up the mark is scaled into the plate's inner box, so the LADDER KEYS ON THE
 *     RENDERED MARK SIZE, not the frame: a 48px frame insets to a 42px mark, which is below the
 *     footnote threshold and correctly drops it. The footnote's own reason for the 48px cut-off is
 *     that it dies at small rendered sizes, and the plate makes the rendered size smaller.
 */
function iconFrame(size) {
  const inset = size <= 16 ? 0 : Math.max(1, Math.floor(size / 16));
  const radius = Math.max(2, Math.round(size / 8));
  const snapped = size <= 24; // 16 and 24 are pixel-authored cuts; never rescale them
  const box = snapped
    ? { x: 0, y: 0, size }
    : { x: inset, y: inset, size: size - inset * 2 };
  const canvas = new Canvas(size, size);
  canvas.paint(
    [
      {
        id: 'plate',
        fill: PLATE_INK,
        contours: [roundedRect(inset, inset, size - inset * 2, size - inset * 2, radius)],
      },
    ],
    { sx: 1, sy: 1, tx: 0, ty: 0 },
  );
  paintMark(canvas, markSource(box.size, 'dark'), box);
  return canvas.rgba();
}

function paintWordmark(canvas, wordmark, { capHeight, x, baseline, color }) {
  const cap = Number(wordmark.attrs['data-cap-height']);
  const scale = capHeight / cap;
  canvas.paint(wordmark.shapes, { sx: scale, sy: scale, tx: x, ty: baseline }, { color });
  return Number(wordmark.attrs['data-advance']) * scale;
}

const written = [];
function write(relativePath, buffer) {
  const full = join(REPO_ROOT, relativePath);
  mkdirSync(dirname(full), { recursive: true });
  writeFileSync(full, buffer);
  written.push(`${relativePath} (${buffer.length} bytes)`);
}

function main() {
  const markDark = svg('mark-dark.svg');
  const markSmallLight = svg('mark-small-light.svg');
  const markSmallDark = svg('mark-small-dark.svg');
  const wordmark = svg('wordmark-outlines.svg');

  // --- app PNGs referenced by tauri.conf.json bundle.icon ---
  for (const size of [32, 128, 256]) {
    const name = size === 256 ? '128x128@2x.png' : `${size}x${size}.png`;
    write(`modules/shell/src-tauri/icons/${name}`, encodePng(iconFrame(size), size, size));
  }

  // --- Windows .ico: 16 and 24 from the dedicated cut, 32 without footnote, 48/256 full ---
  write(
    'modules/shell/src-tauri/icons/icon.ico',
    encodeIco([16, 24, 32, 48, 256].map((size) => ({ size, rgba: iconFrame(size) }))),
  );

  // --- macOS .icns (bundle.icon lists it; tauri-bundler canonicalises every entry) ---
  const icnsSizes = [16, 32, 64, 128, 256, 512, 1024];
  const icnsPng = new Map(
    icnsSizes.map((size) => [size, encodePng(iconFrame(size), size, size)]),
  );
  write(
    'modules/shell/src-tauri/icons/icon.icns',
    encodeIcns([
      ['icp4', icnsPng.get(16)],
      ['icp5', icnsPng.get(32)],
      ['ic11', icnsPng.get(32)], // 16@2x
      ['icp6', icnsPng.get(64)],
      ['ic12', icnsPng.get(64)], // 32@2x
      ['ic07', icnsPng.get(128)],
      ['ic08', icnsPng.get(256)],
      ['ic13', icnsPng.get(256)], // 128@2x
      ['ic09', icnsPng.get(512)],
      ['ic14', icnsPng.get(512)], // 256@2x
      ['ic10', icnsPng.get(1024)], // 512@2x
    ]),
  );

  // --- ui-web favicon: icon-class, so it carries the plate and the dark colorway ---
  // A browser tab is as uncontrolled a ground as a desktop is: light chrome, dark chrome, a theme
  // nobody predicted. The favicon therefore packages the plate like the .ico does, which also
  // means it has ONE colorway instead of a prefers-color-scheme pair -- the ground is now ours.
  const geometry = readFileSync(join(BRAND_DIR, 'mark-small-dark.svg'), 'utf8');
  const massD = /<path id="mass"[^>]*d="([^"]+)"/.exec(geometry)[1];
  const line = attrs(/<rect id="answer-line"([^>]*)\/>/.exec(geometry)[1]);
  const hex = ([r, g, b]) =>
    `#${[r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('')}`;
  write(
    'modules/ui-web/public/favicon.svg',
    Buffer.from(
      `<!-- GENERATED by brand/generate.mjs from brand/mark-small-dark.svg - do not hand-edit. -->\n` +
        `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" shape-rendering="crispEdges">\n` +
        `  <rect x="0" y="0" width="16" height="16" rx="2" fill="${hex(PLATE_INK)}"/>\n` +
        `  <path fill="${hex(markSmallDark.shapes[0].fill)}" d="${massD}"/>\n` +
        `  <rect fill="${hex(markSmallDark.shapes[1].fill)}" x="${line.x}" y="${line.y}" width="${line.width}" height="${line.height}"/>\n` +
        `</svg>\n`,
      'utf8',
    ),
  );

  // --- NSIS welcome/finish sidebar: 164x314, flat dark field, hard right edge at the seam ---
  // Mark artboard x is chosen so the mass's LEFT WALL (viewBox x=6) lands on the same 24px margin
  // the wordmark starts from; without that the two elements read as two unrelated left edges.
  const sidebar = new Canvas(164, 314, [0x0e, 0x0f, 0x12]);
  const markScale = 104 / markDark.viewBox.w;
  sidebar.paint(markDark.shapes, {
    sx: markScale,
    sy: markScale,
    tx: 24 - 6 * markScale,
    ty: 44,
  });
  const sidebarAdvance = paintWordmark(sidebar, wordmark, {
    capHeight: 18,
    x: 24,
    baseline: 282,
    color: [0xec, 0xee, 0xf1],
  });
  if (24 + sidebarAdvance > 152) throw new Error('sidebar wordmark overruns its right margin');
  write('modules/shell/src-tauri/nsis/sidebar.bmp', encodeBmp24(sidebar.rgba(), 164, 314));

  // --- NSIS header: 150x57 on MUI's white header ground (MUI_BGCOLOR defaults to FFFFFF), so the
  // LIGHT colorway, not the dark one the icons take.
  const header = new Canvas(150, 57, [0xff, 0xff, 0xff]);
  const headerScale = 24 / markSmallLight.viewBox.w;
  const headerMarkX = 12 - 1 * headerScale; // put the mass's left wall (x=1) on a 12px margin
  header.paint(markSmallLight.shapes, {
    sx: headerScale,
    sy: headerScale,
    tx: headerMarkX,
    ty: 28.5 - 8 * headerScale, // centre the 16-unit grid on the 57px band
  });
  const headerAdvance = paintWordmark(header, wordmark, {
    capHeight: 14,
    x: headerMarkX + 15 * headerScale + 9, // mass right wall (x=15) + a 9px gap
    baseline: 35.5, // cap centred on the mark's vertical centre
    color: [0x16, 0x18, 0x1c],
  });
  if (headerMarkX + 15 * headerScale + 9 + headerAdvance > 142) {
    throw new Error('header wordmark overruns its right margin');
  }
  write('modules/shell/src-tauri/nsis/header.bmp', encodeBmp24(header.rgba(), 150, 57));

  console.log(`brand/generate.mjs wrote ${written.length} assets:`);
  for (const line of written) console.log(`  ${line}`);
}

const invokedDirectly =
  process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop());
if (invokedDirectly) main();

export { Canvas, coverage, iconFrame };

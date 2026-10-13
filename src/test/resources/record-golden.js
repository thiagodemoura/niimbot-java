// Drives the ORIGINAL niimbot.js against a fake GATT printer and records every
// BLE write, byte for byte, for a set of scenarios. Output: JSON golden file.
"use strict";
const DRIVER = process.argv[2], OUT = process.argv[3];
let device;
Object.defineProperty(globalThis, "navigator", { configurable: true, value: {
  platform: "Win32", userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
  bluetooth: { requestDevice: async () => device } } });

// Deterministic test pattern, shared with the Java test (GoldenTest.pattern).
function black(x, y, seed) {
  if (y < 10 || (y >= 50 && y < 70)) return false;            // blank bands → 0x84 runs
  return ((x * 7 + (y >> 2) * 13 + seed * 5) % 11) < 4;          // rows repeat in groups of 4 → RLE
}
let seed = 0, W = 0, H = 0;
globalThis.fetch = async (url) => { seed = Number(url.split(":")[1]); return { blob: async () => ({}) }; };
globalThis.createImageBitmap = async () => ({});
globalThis.document = { createElement: () => ({ width: 0, height: 0, getContext: () => ({
  fillStyle: "", fillRect() {}, drawImage() {},
  getImageData(_x, _y, w, h) {
    const d = new Uint8ClampedArray(w * h * 4);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4, v = black(x, y, seed) ? 0 : 255;
      d[i] = d[i + 1] = d[i + 2] = v; d[i + 3] = 255;
    }
    return { data: d };
  } }) }) };

let notify = null, identifyId = null, pageCounter = 0, writes = [];
function frame(cmd, data) {
  const p = new Uint8Array(7 + data.length);
  p[0] = 0x55; p[1] = 0x55; p[2] = cmd; p[3] = data.length;
  let c = cmd ^ data.length;
  for (let i = 0; i < data.length; i++) { p[4 + i] = data[i]; c ^= data[i]; }
  p[4 + data.length] = c & 0xff; p[5 + data.length] = 0xaa; p[6 + data.length] = 0xaa;
  return new DataView(p.buffer);
}
const answer = (cmd, data) => notify && notify({ target: { value: frame(cmd, data) } });
let copiesThisPage = 1;
function handle(bytes) {
  if (bytes[0] === 0x03) return;
  const cmd = bytes[2], sub = bytes[4];
  switch (cmd) {
    case 0xa5: { const d = new Array(13).fill(0); d[11] = 3; d[12] = 1; answer(0xb5, d); break; }
    case 0x40: if (sub === 0x08) answer(0x48, identifyId); else answer(0x40 + sub, [0x00]); break;
    case 0xdc: answer(0xd9, [0x1f, 0x50, 0x50, 0x48, 0, 0, 1, 0, 0, 0, 0, 0, 0]); break;
    case 0x21: answer(0x31, [0x01]); break;
    case 0x23: answer(0x33, [0x01]); break;
    case 0x01: pageCounter = 0; answer(0x02, [0x01]); break;
    case 0x03: answer(0x04, [0x01]); break;
    case 0x13: copiesThisPage = (bytes[8] << 8) | bytes[9]; answer(0x14, [0x01]); break;
    case 0xe3: pageCounter += copiesThisPage; answer(0xe4, [0x01]); break;
    case 0xf3: answer(0xf4, [0x01]); break;
    case 0xa3: answer(0xb3, [(pageCounter >> 8) & 0xff, pageCounter & 0xff, 100, 100, 0, 0, 0, 0, 0, 0]); break;
  }
}
const hex = (b) => Buffer.from(b).toString("hex");
const characteristic = {
  properties: { write: true, writeWithoutResponse: true },
  async startNotifications() {},
  addEventListener(t, fn) { if (t === "characteristicvaluechanged") notify = fn; },
  async writeValueWithoutResponse(b) { writes.push("n:" + hex(b)); handle(b); },
  async writeValueWithResponse(b) { writes.push("r:" + hex(b)); handle(b); },
};
const gatt = { connected: false,
  async connect() { this.connected = true; return { getPrimaryService: async () => ({ getCharacteristic: async () => characteristic }) }; },
  disconnect() { this.connected = false; } };
device = { name: "TEST", gatt, addEventListener() {} };

require(DRIVER);
const N = globalThis.Niimbot;
N.PAGE_WAIT_MS = 2000; N.PACE_MS = 0;
console.log = () => {};

const MODELS = {   // registry.json entries
  b1:    { id: 4096, m: { name_prefixes: ["B1"], task: "b1", density: 3, label_type: 1, speed: 1 } },
  b1pro: { id: 4097, m: { name_prefixes: ["B1"], task: "v4", density: 3, label_type: 1, speed: 1 } },
  m2h:   { id: 4608, m: { name_prefixes: ["M2"], task: "b1", density: 3, label_type: 1, speed: 1 } },
  d11h:  { id: 528,  m: { name_prefixes: ["D11"], task: "v4", density: 3, label_type: 1, speed: 1 } },
  d110:  { id: 2304, m: { name_prefixes: ["D110"], task: "b1", density: 3, label_type: 1, speed: 1 } },
};
const SCEN = [
  { name: "b1_single",       model: "b1",    w: 100, h: 120, seeds: [1], copies: 1, density: 3 },
  { name: "b1_copies3",      model: "b1",    w: 384, h: 240, seeds: [2], copies: 3, density: 4 },
  { name: "b1pro_single",    model: "b1pro", w: 100, h: 120, seeds: [3], copies: 1, density: 3 },
  { name: "b1pro_copies2",   model: "b1pro", w: 576, h: 354, seeds: [4], copies: 2, density: 2 },
  { name: "b1pro_batch4",    model: "b1pro", w: 120, h: 80,  seeds: [5, 6, 7, 8], copies: 1, density: 3 },
  { name: "m2h_batch3",      model: "m2h",   w: 567, h: 354, seeds: [9, 10, 11], copies: 1, density: 3 },
  { name: "d11h_single",     model: "d11h",  w: 144, h: 354, seeds: [12], copies: 1, density: 5 },
  { name: "d110_copies3",    model: "d110",  w: 96,  h: 400, seeds: [13], copies: 3, density: 3 },
  { name: "d110_batch2",     model: "d110",  w: 96,  h: 400, seeds: [14, 15], copies: 1, density: 1 },
];
(async () => {
  const out = [];
  for (const s of SCEN) {
    await N.disconnect(); gatt.connected = false; writes = []; pageCounter = 0;
    identifyId = [(MODELS[s.model].id >> 8) & 0xff, MODELS[s.model].id & 0xff];
    const opts = { model: MODELS[s.model].m, size: { w_px: s.w, h_px: s.h }, copies: s.copies, density: s.density };
    if (s.seeds.length === 1) await N.printImage("seed:" + s.seeds[0], opts);
    else await N.printBatch(s.seeds.map((x) => "seed:" + x), opts);
    out.push({ ...s, writes });
  }
  const txt = out.map((o) => `# ${o.name} ${o.model} ${o.w} ${o.h} ${o.copies} ${o.density} ${o.seeds.join(",")}\n` + o.writes.join("\n")).join("\n") + "\n";
  require("fs").writeFileSync(OUT, "% Recorded from the original niimbot.js (iscarelli/niimbot-web-bluetooth) driving a fake GATT printer.\n% Every BLE write in order; n: = writeValueWithoutResponse, r: = writeValueWithResponse.\n" + txt);
  process.stderr.write("scenarios: " + out.map((o) => o.name + "=" + o.writes.length).join(", ") + "\n");
})().catch((e) => { process.stderr.write("ERR " + e.stack + "\n"); process.exit(1); });

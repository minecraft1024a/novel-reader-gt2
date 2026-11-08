/*
 * Phone -> GT2 receiver.
 *
 * This follows Huawei's Lite Wearable P2P guide: the phone sends a Message
 * whose payload is a File, and the received file is placed in
 * internal://app/<name>. The app moves it into imported/ so the book shelf
 * can list it. A compact NR1 packet fallback is retained for the simulator.
 */
console.info("novel-reader phoneTransport onImport");

const file = requireNative("system.file");
const CHUNK_PREFIX = "NR1";
let subscribed = false;
let active = null;
let writeQueue = [];
let writing = false;

const transport = {
  init(done) {
    if (typeof FeatureAbility === "undefined" || !FeatureAbility.subscribeMsg) {
      console.warn("FeatureAbility unavailable; phone receiver disabled");
      if (done) done();
      return;
    }
    if (!subscribed) {
      subscribed = true;
      FeatureAbility.subscribeMsg({
        success: data => receive(data),
        fail: (data, code) => console.error("P2P receiver failed " + code + " " + data)
      });
    }
    if (done) done();
  },
  destroy() {
    if (subscribed && typeof FeatureAbility !== "undefined" && FeatureAbility.unsubscribeMsg) FeatureAbility.unsubscribeMsg();
    subscribed = false;
    active = null;
    writeQueue = [];
    writing = false;
  }
};

function receive(data) {
  if (!data) return;
  if (data.isFileType) {
    const fileInfo = (data.file && typeof data.file === "object") ? data.file : null;
    return receiveFile(data.name || (fileInfo && (fileInfo.name || fileInfo.uri)) || data.file || "book.txt");
  }
  const message = data.message || data;
  if (typeof message === "string" && message.slice(0, CHUNK_PREFIX.length) === CHUNK_PREFIX) receivePacket(message);
}

function receiveFile(name) {
  const sourceName = String(name).slice(String(name).lastIndexOf("/") + 1);
  const safe = safeFileName(sourceName);
  const move = () => file.move({
      srcUri: "internal://app/" + safe,
      dstUri: "internal://app/imported/" + safe,
      fail: (data, code) => console.warn("P2P file move failed " + code + " " + data),
      success: () => console.info("P2P novel received " + safe)
    });
  file.mkdir({
    uri: "internal://app/imported",
    recursive: true,
    complete: move
  });
}

function receivePacket(message) {
  let packet;
  try { packet = JSON.parse(message.slice(CHUNK_PREFIX.length)); } catch (e) { return; }
  if (!packet || !packet.type || !packet.id) return;
  if (packet.type === "begin") {
    const safe = safeFileName(packet.name || (packet.id + ".txt"));
    active = { id: packet.id, uri: "internal://app/imported/" + safe, expected: Number(packet.size) || 0, received: 0, next: 0, name: safe };
    writeQueue = [];
    writing = false;
    file.mkdir({ uri: "internal://app/imported", recursive: true });
    file.delete({ uri: active.uri });
    return;
  }
  if (!active || packet.id !== active.id) return;
  if (packet.type === "chunk" && Number(packet.seq) === active.next) {
    active.next += 1;
    writeQueue.push(packet.data || "");
    drain();
  } else if (packet.type === "end") {
    writeQueue.push(null);
    drain();
  }
}

function drain() {
  if (writing || !active || writeQueue.length === 0) return;
  const part = writeQueue.shift();
  if (part === null) {
    if (active.received !== active.expected) console.warn("NR1 size mismatch");
    else console.info("NR1 novel received " + active.name);
    active = null;
    return;
  }
  writing = true;
  const text = decodeBase64Utf8(part);
  file.writeText({
    uri: active.uri,
    text: text,
    append: true,
    success: () => { if (active) active.received += utf8ByteLength(text); },
    complete: () => { writing = false; setTimeout(drain, 0); }
  });
}

function safeFileName(name) {
  let value = String(name).replace(/[\\/:*?"<>|]/g, "_");
  if (!value) value = "book.txt";
  return value.length > 72 ? value.slice(value.length - 72) : value;
}

function decodeBase64Utf8(value) {
  const table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  const bytes = [];
  let buffer = 0;
  let bits = 0;
  for (let i = 0; i < value.length; i++) {
    if (value.charAt(i) === "=") break;
    const n = table.indexOf(value.charAt(i));
    if (n < 0) continue;
    buffer = (buffer << 6) | n;
    bits += 6;
    if (bits >= 8) { bits -= 8; bytes.push((buffer >> bits) & 255); }
  }
  let text = "";
  for (let i = 0; i < bytes.length;) {
    const a = bytes[i++];
    if (a < 128) text += String.fromCharCode(a);
    else if (a < 224 && i < bytes.length) text += String.fromCharCode(((a & 31) << 6) | (bytes[i++] & 63));
    else if (a < 240 && i + 1 < bytes.length) text += String.fromCharCode(((a & 15) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63));
    else if (i + 2 < bytes.length) {
      const cp = ((a & 7) << 18) | ((bytes[i++] & 63) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63);
      text += String.fromCharCode(0xD800 + ((cp - 0x10000) >> 10), 0xDC00 + ((cp - 0x10000) & 1023));
    }
  }
  return text;
}

function utf8ByteLength(value) {
  let total = 0;
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    if (code < 128) total += 1;
    else if (code < 2048) total += 2;
    else if (code >= 0xD800 && code <= 0xDBFF && i + 1 < value.length) { total += 4; i += 1; }
    else total += 3;
  }
  return total;
}

export default transport;

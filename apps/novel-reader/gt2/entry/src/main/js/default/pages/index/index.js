import router from "../../router.js";
import memory from "../../memory.js";
import uiSizes from "../../uiSizes.js";
import headerTimeBattery from "../../headerTimeBattery.js";
import phoneTransport from "../../phoneTransport.js";

const imports = {
  app: requireNative("system.app"), battery: requireNative("system.battery"),
  brightness: requireNative("system.brightness"), device: requireNative("system.device"),
  file: requireNative("system.file"), router: router, storage: requireNative("system.storage"),
  vibrator: requireNative("system.vibrator"),
  memory: memory, uiSizes: uiSizes, headerTimeBattery: headerTimeBattery,
  phoneTransport: phoneTransport
};

export default {
  onInit() {
    $app.setImports(imports);
    initImport(0, Object.keys(imports), () => router.replace({ uri: "pages/home/home" }));
  }
};

function initImport(index, keys, done) {
  if (index >= keys.length) return done();
  const value = imports[keys[index]];
  if (!value || !value.init) return setTimeout(() => initImport(index + 1, keys, done), 0);
  let finished = false;
  const timer = setTimeout(() => {
    if (!finished) { finished = true; initImport(index + 1, keys, done); }
  }, 2500);
  value.init(() => {
    if (finished) return;
    finished = true; clearTimeout(timer);
    setTimeout(() => initImport(index + 1, keys, done), 0);
  });
}

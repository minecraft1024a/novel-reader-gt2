console.info("novel-reader memory.js onImport");

const defaults = {
  currentBook: "",
  currentBookName: "",
  currentBookSize: 0,
  currentPos: 0,
  viewTextHistory: [],
  theme: "夜间",
  bgColor: "#000000",
  textColor: "#FFFFFF",
  fontSize: "30px",
  autoPagerSpeed: 5,
  keyMap: {
    tapLeft: "上一页", tapRight: "下一页", holdLeft: "自动翻页", holdRight: "自动翻页",
    swipeLeft: "下一页", swipeRight: "上一页", swipeUp: "显示工具栏", swipeDown: "显示工具栏"
  }
};
const memory = {};

function clone(value) {
  if (value && typeof value === "object") return JSON.parse(JSON.stringify(value));
  return value;
}

function init(done) {
  const keys = Object.keys(defaults);
  let index = 0;
  function next() {
    if (index >= keys.length) return done && done(memory);
    const key = keys[index++];
    $app.getImports().file.readText({
      uri: "internal://app/kvstore/" + key,
      fail: () => { memory[key] = clone(defaults[key]); next(); },
      success: data => {
        try { memory[key] = JSON.parse(data.text); }
        catch (e) { memory[key] = clone(defaults[key]); }
        next();
      }
    });
  }
  next();
}

function save(key) {
  if (memory[key] === undefined) return;
  $app.getImports().file.writeText({
    uri: "internal://app/kvstore/" + key,
    text: JSON.stringify(memory[key]),
    fail: (data, code) => console.error("save " + key + " failed " + code + " " + data)
  });
}

memory.init = init;
memory.save = save;
export default memory;

const ACTIONS = ["上一页", "下一页", "自动翻页", "显示工具栏", "返回书架", "无" ];
const SLOTS = ["tapLeft", "tapRight", "holdLeft", "holdRight", "swipeLeft", "swipeRight", "swipeUp", "swipeDown"];
const LABELS = { tapLeft: "左侧轻触", tapRight: "右侧轻触", holdLeft: "左侧长按", holdRight: "右侧长按", swipeLeft: "向左滑", swipeRight: "向右滑", swipeUp: "向上滑", swipeDown: "向下滑" };

export default {
  data: {
    uiSizes: $app.getImports().uiSizes, timeBatteryStr: "",
    theme: $app.getImports().memory.theme, fontSize: $app.getImports().memory.fontSize,
    autoPagerSpeed: $app.getImports().memory.autoPagerSpeed,
    tapLeft: "", tapRight: "", holdLeft: "", holdRight: "", swipeLeft: "", swipeRight: "", swipeUp: "", swipeDown: ""
  },
  onInit() {
    const map = $app.getImports().memory.keyMap;
    for (let i = 0; i < SLOTS.length; i++) this[SLOTS[i]] = map[SLOTS[i]] || ACTIONS[0];
    $app.getImports().headerTimeBattery.subscribe(() => {
      this.timeBatteryStr = $app.getImports().headerTimeBattery.time + "  " + $app.getImports().headerTimeBattery.battery;
    });
  },
  onShow() { if (this.$refs.settingList && this.$refs.settingList.rotation) this.$refs.settingList.rotation({ focus: true }); },
  onHide() { if (this.$refs.settingList && this.$refs.settingList.rotation) this.$refs.settingList.rotation({ focus: false }); },
  changeTheme(name) {
    const colors = {
      "夜间": ["#000000", "#FFFFFF"], "日间": ["#FFFFFF", "#111111"],
      "深灰": ["#303030", "#EEEEEE"], "护眼": ["#332B20", "#F4E6C8"]
    };
    const pair = colors[name] || colors["夜间"];
    this.theme = name;
    $app.getImports().memory.theme = name; $app.getImports().memory.bgColor = pair[0]; $app.getImports().memory.textColor = pair[1];
    $app.getImports().memory.save("theme"); $app.getImports().memory.save("bgColor"); $app.getImports().memory.save("textColor");
  },
  changeFontSize(size) { this.fontSize = size; $app.getImports().memory.fontSize = size; $app.getImports().memory.save("fontSize"); },
  speedDown() { this.autoPagerSpeed = Math.max(1, this.autoPagerSpeed - 1); $app.getImports().memory.autoPagerSpeed = this.autoPagerSpeed; $app.getImports().memory.save("autoPagerSpeed"); },
  speedUp() { this.autoPagerSpeed = Math.min(30, this.autoPagerSpeed + 1); $app.getImports().memory.autoPagerSpeed = this.autoPagerSpeed; $app.getImports().memory.save("autoPagerSpeed"); },
  cycle(slot) {
    const current = this[slot];
    const index = ACTIONS.indexOf(current);
    const next = ACTIONS[(index + 1) % ACTIONS.length];
    this[slot] = next;
    $app.getImports().memory.keyMap[slot] = next;
    $app.getImports().memory.save("keyMap");
  },
  back() { $app.getImports().router.replace({ uri: "pages/home/home" }); },
  swipeBack(data) { if (data.direction === "right") this.back(); }
};

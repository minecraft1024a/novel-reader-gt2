const MAX_BOOKS = 8;
let pollTimer = null;

export default {
  data: {
    uiSizes: $app.getImports().uiSizes, timeBatteryStr: "", books: [],
    empty: false, status: "正在扫描 imported/", failData: ""
  },
  onInit() {
    $app.getImports().headerTimeBattery.subscribe(() => {
      this.timeBatteryStr = $app.getImports().headerTimeBattery.time + "  " + $app.getImports().headerTimeBattery.battery;
    });
    this.refresh();
  },
  onShow() {
    this.refresh();
    pollTimer = setInterval(() => this.refresh(), 5000);
    if (this.$refs.bookList && this.$refs.bookList.rotation) this.$refs.bookList.rotation({ focus: true });
  },
  onHide() {
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = null;
    if (this.$refs.bookList && this.$refs.bookList.rotation) this.$refs.bookList.rotation({ focus: false });
  },
  onDestroy() {
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = null;
  },
  refresh() {
    this.adoptRootFiles(() => this.refreshShelf());
  },
  adoptRootFiles(done) {
    const file = $app.getImports().file;
    file.list({
      uri: "internal://app",
      fail: () => done && done(),
      success: data => {
        const list = data.fileList || [];
        const candidates = [];
        for (let i = 0; i < list.length; i++) {
          if (list[i].type !== "file" && list[i].type !== 0) continue;
          const uri = list[i].uri || "";
          const name = uri.slice(uri.lastIndexOf("/") + 1);
          if (isText(name)) candidates.push({ uri: uri, name: name });
        }
        if (candidates.length === 0) { if (done) done(); return; }
        let left = candidates.length;
        file.mkdir({
          uri: "internal://app/imported",
          recursive: true,
          complete: () => {
            candidates.forEach(item => file.move({
              srcUri: item.uri,
              dstUri: "internal://app/imported/" + item.name,
              complete: () => { left--; if (left === 0 && done) done(); }
            }));
          }
        });
      }
    });
  },
  refreshShelf() {
    const file = $app.getImports().file;
    file.list({
      uri: "internal://app/imported",
      fail: (data, code) => {
        this.books = []; this.empty = true;
        this.status = code === 301 ? "把 .txt / .md 发送到手表后会出现在这里" : ("读取失败 " + code);
      },
      success: data => {
        const list = data.fileList || [];
        const result = [];
        for (let i = 0; i < list.length && result.length < MAX_BOOKS; i++) {
          if (list[i].type === "file" || list[i].type === 0) {
            const uri = list[i].uri;
            const name = uri.slice(uri.lastIndexOf("/") + 1);
            if (isText(name)) result.push({ uri: uri, name: name, length: list[i].length || 0 });
          }
        }
        this.books = result;
        this.empty = result.length === 0;
        this.status = this.empty ? "把 .txt / .md 发送到手表后会出现在这里" : (result.length + " 本书");
      }
    });
  },
  openBook(book) {
    $app.getImports().memory.currentBook = book.uri;
    $app.getImports().memory.currentBookName = book.name;
    $app.getImports().memory.currentBookSize = book.length;
    $app.getImports().memory.currentPos = 0;
    $app.getImports().memory.save("currentBook");
    $app.getImports().memory.save("currentBookName");
    $app.getImports().memory.save("currentBookSize");
    $app.getImports().router.replace({ uri: "pages/viewer_text/viewer_text" });
  },
  openSettings() { $app.getImports().router.replace({ uri: "pages/settings/settings" }); },
  exitApp() { $app.getImports().app.terminate(); },
  swipeBack(data) { if (data.direction === "right") this.exitApp(); }
};
function isText(name) {
  const lower = name.toLowerCase();
  return lower.indexOf(".txt") === lower.length - 4 || lower.indexOf(".md") === lower.length - 3 || lower.indexOf(".markdown") === lower.length - 9;
}

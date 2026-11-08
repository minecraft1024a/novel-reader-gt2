const imports = {};
export default {
  onCreate() { console.info("NovelReader app onCreate"); },
  onDestroy() { console.info("NovelReader app onDestroy"); },
  setImports(value) { for (const key in value) imports[key] = value[key]; },
  getImports() { return imports; }
};

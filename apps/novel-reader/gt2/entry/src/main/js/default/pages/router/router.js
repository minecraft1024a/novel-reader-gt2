export default {
  data: { uri: "", params: {} },
  onInit() {
    requireNative("system.router").replace({ uri: this.uri, params: this.params });
  }
};

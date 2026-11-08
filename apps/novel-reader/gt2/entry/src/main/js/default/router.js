const router = {
  replace(data) {
    setTimeout(() => requireNative("system.router").replace({
      uri: "pages/router/router",
      params: { uri: data.uri, params: data.params || {} }
    }), 0);
  }
};
export default router;

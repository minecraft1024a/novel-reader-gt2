const sizes = {
  screenWidth: 390, screenHeight: 390, isCircle: true,
  uiWidth: 276, uiHeight: 276, topMargin: 57, leftMargin: 57,
  init(done) {
    $app.getImports().device.getInfo({
      success: data => {
        sizes.screenWidth = data.windowWidth;
        sizes.screenHeight = data.windowHeight;
        sizes.isCircle = data.screenShape === "circle";
        const ui = getUi(data.windowWidth, data.windowHeight);
        sizes.uiWidth = ui[0]; sizes.uiHeight = ui[1];
        sizes.topMargin = (data.windowHeight - ui[1]) / 2;
        sizes.leftMargin = (data.windowWidth - ui[0]) / 2;
      },
      complete: () => done && done()
    });
  }
};
function getUi(width, height) {
  if (width === 280 && height === 456) return [276, 360];
  if (width === 336 && height === 480) return [336, 396];
  if (width === 408 && height === 480) return [336, 396];
  if (width === 390 && height === 390) return [276, 276];
  if (width === 454 && height === 454) return [336, 306];
  if (width === 466 && height === 466) return [336, 306];
  return [276, 276];
}
export default sizes;

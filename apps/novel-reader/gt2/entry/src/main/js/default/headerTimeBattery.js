const state = { time: "--:--", battery: "--%", listeners: [], timer: null };
function init(done) { update(); state.timer = setInterval(update, 30000); if (done) done(); }
function update() {
  const now = new Date();
  const h = now.getHours() < 10 ? "0" + now.getHours() : "" + now.getHours();
  const m = now.getMinutes() < 10 ? "0" + now.getMinutes() : "" + now.getMinutes();
  state.time = h + ":" + m;
  $app.getImports().battery.getStatus({ success: data => { state.battery = data.level + "%"; } });
  for (let i = 0; i < state.listeners.length; i++) state.listeners[i]();
}
function subscribe(callback) { if (callback) state.listeners.push(callback); if (callback) callback(); }
function destroy() { if (state.timer) clearInterval(state.timer); state.timer = null; state.listeners = []; }
export default { init, subscribe, destroy, get time() { return state.time; }, get battery() { return state.battery; } };

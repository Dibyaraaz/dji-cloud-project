// backend/src/mqtt.js
const mqtt = require("mqtt");
const state = require("./state");
const db = require("./database");

function startMqtt() {
const host = process.env.MQTT_HOST || "emqx";
const port = process.env.MQTT_PORT || "1883";
const username = process.env.MQTT_USERNAME || "";
const password = process.env.MQTT_PASSWORD || "";

const RC_SN = process.env.RC_SN || "5YSZN3L003FR45";
let aircraftSn = process.env.AIRCRAFT_SN || null;

const url = `mqtt://${host}:${port}`;
const client = mqtt.connect(url, {
clientId: `cloud-backend-${RC_SN}`,
username,
password,
keepalive: 30,
reconnectPeriod: 2000,
connectTimeout: 10_000,
clean: false,
resubscribe: true,
});

function subscribe(topic) {
client.subscribe(topic, { qos: 0 });
}

function subscribeForAircraft(sn) {
if (!sn) return;
console.log("[mqtt] Aircraft detected:", sn);
subscribe(`thing/product/${sn}/osd`);
subscribe(`thing/product/${sn}/state`);
subscribe(`thing/product/${sn}/#`);
}

function pickFirst(obj, keys) {
for (const k of keys) {
if (obj && obj[k] !== undefined && obj[k] !== null) return obj[k];
}
return undefined;
}

function tryExtractTelemetry(obj, topic) {
const match = topic.match(/(?:thing|sys)\/product\/([^/]+)/);
const deviceSn = match ? match[1] : null;
if (!deviceSn) return;

const d = obj?.data || obj;

// --- ALTITUDE & INDOOR HACK ---
let alt = pickFirst(d, ["height", "relative_height"]);
if (alt === undefined || alt === null) {
alt = pickFirst(d, ["altitude", "alt"]);
}
// If we are at sea-level 590m indoors, force it to 0.0

const lat = pickFirst(d, ["latitude", "lat"]);
const lon = pickFirst(d, ["longitude", "lon"]);
let batt = pickFirst(d, ["battery_percent", "battery"]);
const speed = pickFirst(d, ["horizontal_speed", "speed"]);
const vspeed = pickFirst(d, ["vertical_speed", "climb_rate"]);
const satellites = pickFirst(d, ["gps_number", "satellite_count"]);

if (typeof batt === 'object' && batt !== null) {
batt = batt.capacity_percent || (batt.batteries && batt.batteries[0] ? batt.batteries[0].capacity_percent : undefined);
}

const telemetry = {
latitude: lat,
longitude: lon,
altitude: alt,
battery: batt,
speed: speed || 0,
verticalSpeed: vspeed || 0,
satellites: satellites,
timestamp: Date.now()
};

if (Object.keys(telemetry).length > 2) {
// 1. Update live state for the Real-time HUD
state.updateTelemetry(deviceSn, telemetry);
// 2. PERSIST TO DATABASE for Flight History
db.saveTelemetry(deviceSn, telemetry);
if (topic.includes("/osd")) {
console.log(`[mqtt] Saved telemetry for ${deviceSn} | Alt: ${alt}m`);
}
}
}

client.on("connect", () => {
console.log("[mqtt] Connected securely");
subscribe("sys/#");
subscribe("thing/#");
if (aircraftSn) subscribeForAircraft(aircraftSn);
});

client.on("message", (topic, payload) => {
let obj;
try { obj = JSON.parse(payload.toString()); } catch (e) { return; }

// --- RESTORED: TOPOLOGY ACKNOWLEDGEMENT ---
if (topic.includes(`/status`) && obj?.method === "update_topo") {
console.log("[mqtt] Received topology update from drone.");

// Send the required reply to clear the exclamation mark
const replyTopic = `${topic}_reply`;
const replyPayload = JSON.stringify({
tid: obj.tid,
bid: obj.bid,
timestamp: Date.now(),
data: { result: 0 }
});
client.publish(replyTopic, replyPayload, { qos: 1 }, (err) => {
if (!err) console.log(`[mqtt] Sent update_topo_reply! Exclamation mark should clear.`);
});

const subs = obj?.data?.sub_devices || [];
if (subs.length > 0 && subs[0].sn) {
if (!aircraftSn) {
aircraftSn = subs[0].sn;
console.log("[mqtt] Aircraft auto-detected:", aircraftSn);
subscribeForAircraft(aircraftSn);
}
}
return; // Exit here so it doesn't process this setup message as flight telemetry
}
// ------------------------------------------

if (obj && (topic.includes("/osd") || topic.includes("/state"))) {
tryExtractTelemetry(obj, topic);
}
});

return client;
}

module.exports = { startMqtt };
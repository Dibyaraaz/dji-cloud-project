const fs = require("fs");
require("dotenv").config();

function loadPilot2PublicKeyPem() {
  const p = process.env.PILOT2_PUBLIC_KEY_PATH;
  if (!p) return "";
  try {
    return fs.readFileSync(p, "utf8");
  } catch (e) {
    console.warn("[config] Could not read public key at", p, e?.message || e);
    return "";
  }
}

const express = require("express");
const morgan = require("morgan");
const cors = require("cors");
const path = require("path");
const http = require("http");
const { startMqtt } = require("./mqtt");
const { initDatabase } = require("./database");
const { initWebSocket } = require("./websocket");

const app = express();
const server = http.createServer(app);

app.use(cors());
app.use(express.json({ limit: "2mb" }));
app.use(morgan("dev"));

/**
 * Basic health check
 */
app.get("/health", (req, res) => {
  res.json({ ok: true, ts: new Date().toISOString() });
});
// Dashboard route
app.get('/dashboard', (req, res) => {
  res.sendFile(path.join(__dirname, 'web', 'dashboard.html'));
});
/**
 * Pilot 2 entry page
 */
app.get(["/", "/pilot-login"], (req, res) => {
  res.sendFile(path.join(__dirname, "web", "pilot-login.html"));
});

app.get("/pilot-login/config.js", (req, res) => {
  // Dynamically extract the host IP from PUBLIC_BASE_URL so you don't 
  // have to hardcode your iPhone hotspot IP every time it changes.
  const publicUrl = process.env.PUBLIC_BASE_URL || "http://127.0.0.1:6789";
  const hostIp = new URL(publicUrl).hostname;

  const cfg = {
    publicBaseUrl: publicUrl,
    mqtt: {
      host: hostIp, 
      port: 1883, // <--- CRITICAL: Must be 8083 for DJI WebSockets
      username: process.env.MQTT_USERNAME || "pilot2",
      password: process.env.MQTT_PASSWORD || "pilot2pass"
    },
    dji: {
      appId: process.env.DJI_APP_ID || "",
      appKey: process.env.DJI_APP_KEY || "",
      license: process.env.DJI_LICENSE || ""
    },
    workspace: {
      platformName: "DJI Cloud Backend",
      workspaceName: "Main Workspace",
      desc: "Telemetry & Control System",
      workspaceId: process.env.WORKSPACE_ID || ""
    },
    crypto: {
      publicKeyPem: loadPilot2PublicKeyPem()
    }
  };
  res.type("application/javascript");
  res.send(`window.__CFG__ = ${JSON.stringify(cfg)};`);
});

/**
 * Client log endpoint
 */
app.post("/api/client-log", (req, res) => {
  console.log("[pilot2-web]", req.body);
  res.json({ ok: true });
});

/**
 * REST API routes
 */
const apiRouter = require("./routes/api");
app.use("/api", apiRouter);

const port = Number(process.env.PORT || 6789);

/**
 * Initialize all services and start server
 */
async function startServer() {
  // Initialize MySQL database
  await initDatabase();

  // Start HTTP server
  server.listen(port, () => {
    console.log("============================================");
    console.log("DJI Cloud API Backend Server");
    console.log("============================================");
    console.log(`Backend listening on :${port}`);
    console.log(`Pilot 2 page: ${process.env.PUBLIC_BASE_URL || `http://<HOST>:${port}`}/pilot-login`);
    console.log(`WebSocket: ws://<HOST>:${port}/ws`);
    console.log("============================================");

    // Initialize WebSocket after server starts
    initWebSocket(server);

    // Start MQTT client and get reference
    const mqttClient = startMqtt();
    
    // Inject MQTT client into API routes for command publishing
    if (mqttClient) {
      apiRouter.setMqttClient(mqttClient);
      console.log('[SERVER] MQTT client injected into API routes');
    }
  });
}

startServer();

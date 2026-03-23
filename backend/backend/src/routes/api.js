// backend/src/routes/api.js
const express = require('express');
const router = express.Router();
const state = require('../state');
const db = require('../database');
const { authenticateUser, authMiddleware, requireRole } = require('../auth');
const ws = require('../websocket');

// MQTT client reference - will be injected by server.js
let mqttClient = null;

/**
 * Function to inject MQTT client from server.js
 */
function setMqttClient(client) {
  mqttClient = client;
  console.log('[API] MQTT client reference set for command publishing');
}

/**
 * Helper function to publish DJI Cloud API commands via MQTT
 */
async function publishDroneCommand(deviceSn, method, data) {
  if (!mqttClient) {
    throw new Error('MQTT client not available');
  }
  
  if (!mqttClient.connected) {
    throw new Error('MQTT not connected');
  }
  
  // DJI Cloud API command topic
  const topic = `thing/product/${deviceSn}/services`;
  
  // DJI Cloud API message format
  const message = {
    bid: `cmd_${Date.now()}`,     // Business ID
    tid: `tx_${Date.now()}`,      // Transaction ID  
    timestamp: Date.now(),
    method: method,
    data: data
  };
  
  const payload = JSON.stringify(message);
  
  return new Promise((resolve, reject) => {
    mqttClient.publish(topic, payload, { qos: 1 }, (err) => {
      if (err) {
        console.error(`[MQTT] Publish failed to ${topic}:`, err);
        reject(err);
      } else {
        console.log(`[MQTT] ✓ Published ${method} to ${deviceSn}`);
        console.log(`[MQTT] Topic: ${topic}`);
        console.log(`[MQTT] Data:`, data);
        resolve(message.tid);
      }
    });
  });
}

/**
 * POST /api/auth/login
 * User authentication
 */
router.post('/auth/login', async (req, res) => {
  const { username, password } = req.body;

  if (!username || !password) {
    return res.status(400).json({ error: 'Username and password required' });
  }

  const result = await authenticateUser(username, password);

  if (!result.success) {
    return res.status(401).json({ error: result.message });
  }

  res.json({
    token: result.token,
    user: result.user
  });
});

/**
 * GET /api/status
 * Backend health check (public)
 */
router.get('/status', (req, res) => {
  const stats = state.getStats();
  
  res.json({
    ok: true,
    timestamp: new Date().toISOString(),
    uptime: process.uptime(),
    mqtt: {
      connected: mqttClient ? mqttClient.connected : false,
      devices: stats.totalDevices
    },
    websocket: {
      clients: ws.getConnectedClientsCount()
    },
    stats: stats
  });
});

/**
 * GET /api/devices
 * List all connected devices (requires auth)
 */
router.get('/devices', authMiddleware, (req, res) => {
  const allDevices = state.getAllDevices();
  const deviceList = [];

  for (const sn in allDevices) {
    const device = allDevices[sn];
    deviceList.push({
      sn: device.sn,
      type: device.type,
      model: device.metadata?.model || "Unknown",
      firstSeen: device.firstSeen,
      lastUpdate: device.lastUpdate,
      online: state.isDeviceOnline(device.sn),
      hasTelemetry: Object.keys(device.telemetry || {}).length > 0
    });
  }

  res.json({
    count: deviceList.length,
    devices: deviceList
  });
});

/**
 * GET /api/telemetry/latest
 * Get latest telemetry for all devices (requires auth)
 */
router.get('/telemetry/latest', authMiddleware, (req, res) => {
  const latest = state.getLatestTelemetry();
  
  res.json({
    timestamp: new Date().toISOString(),
    devices: latest
  });
});

/**
 * GET /api/telemetry/:sn
 * Get telemetry for a specific device (requires auth)
 */
router.get('/telemetry/:sn', authMiddleware, (req, res) => {
  const { sn } = req.params;
  
  if (!sn) {
    return res.status(400).json({ error: 'Missing device serial number' });
  }

  const device = state.getTelemetry(sn);
  
  if (!device) {
    return res.status(404).json({
      error: 'Device not found',
      message: `No device with serial number: ${sn}`
    });
  }

  res.json({
    sn: device.sn,
    type: device.type,
    telemetry: device.telemetry,
    metadata: device.metadata,
    firstSeen: device.firstSeen,
    lastUpdate: device.lastUpdate,
    online: state.isDeviceOnline(device.sn)
  });
});

/**
 * GET /api/telemetry/:sn/history
 * Get historical telemetry (requires auth)
 */
router.get('/telemetry/:sn/history', authMiddleware, async (req, res) => {
  const { sn } = req.params;
  const limit = parseInt(req.query.limit) || 100;

  const history = await db.getDeviceHistory(sn, limit);

  res.json({
    deviceSn: sn,
    count: history.length,
    data: history
  });
});

/**
 * POST /api/command/:sn/takeoff
 * Send takeoff command (requires operator or admin role)
 */
router.post('/command/:sn/takeoff', authMiddleware, requireRole('operator', 'admin'), async (req, res) => {
  const { sn } = req.params;
  
  try {
    const commandId = await db.saveCommand(sn, 'takeoff', {});

    if (!commandId) {
      return res.status(500).json({ error: 'Failed to save command' });
    }

    // DJI Cloud API Takeoff (Action 1). 
    // Drone will start motors, ascend to 1.2m, and hover.
    const tid = await publishDroneCommand(sn, 'flight_control', {
      action: 1  
    });

    await db.updateCommandStatus(commandId, 'sent');

    res.json({
      success: true,
      commandId,
      tid,
      message: `Takeoff command sent to ${sn}`
    });
  } catch (error) {
    console.error('[API] Takeoff command error:', error);
    res.status(500).json({ 
      success: false,
      error: 'Failed to send takeoff command',
      details: error.message
    });
  }
});

/**
 * POST /api/command/:sn/land
 * Send land command (requires operator or admin role)
 */
router.post('/command/:sn/land', authMiddleware, requireRole('operator', 'admin'), async (req, res) => {
  const { sn } = req.params;

  try {
    const commandId = await db.saveCommand(sn, 'land', {});

    if (!commandId) {
      return res.status(500).json({ error: 'Failed to save command' });
    }

    // DJI Cloud API Auto-Land (Action 2).
    const tid = await publishDroneCommand(sn, 'flight_control', {
      action: 2  
    });

    await db.updateCommandStatus(commandId, 'sent');

    res.json({
      success: true,
      commandId,
      tid,
      message: `Land command sent to ${sn}`
    });
  } catch (error) {
    console.error('[API] Land command error:', error);
    res.status(500).json({ 
      success: false,
      error: 'Failed to send land command',
      details: error.message
    });
  }
});

/**
 * POST /api/command/:sn/rth
 * Send return-to-home command (requires operator or admin role)
 */
router.post('/command/:sn/rth', authMiddleware, requireRole('operator', 'admin'), async (req, res) => {
  const { sn } = req.params;

  try {
    const commandId = await db.saveCommand(sn, 'return_to_home', {});

    if (!commandId) {
      return res.status(500).json({ error: 'Failed to save command' });
    }

    // DJI Cloud API Return-to-Home (Action 6).
    const tid = await publishDroneCommand(sn, 'flight_control', {
      action: 6  
    });

    await db.updateCommandStatus(commandId, 'sent');

    res.json({
      success: true,
      commandId,
      tid,
      message: `Return-to-home command sent to ${sn}`
    });
  } catch (error) {
    console.error('[API] RTH command error:', error);
    res.status(500).json({ 
      success: false,
      error: 'Failed to send RTH command',
      details: error.message
    });
  }
});

/**
 * POST /api/command/:sn/camera/photo
 * Send command to snap a photo using the main camera
 */
router.post('/command/:sn/camera/photo', authMiddleware, requireRole('operator', 'admin'), async (req, res) => {
  const { sn } = req.params;

  try {
    console.log(`[CMD] Forcing ${sn} camera to Photo Mode...`);
    
    // 1. Force the drone into "Shoot Photo" mode (mode: 0)
    await publishDroneCommand(sn, 'camera_mode_switch', {
      payload_index: "1-0-0",
      camera_mode: 0
    });

    // 2. Wait 1.5 seconds for the physical camera lens to adjust
    await new Promise(resolve => setTimeout(resolve, 1500));

    console.log(`[CMD] Firing shutter on ${sn}...`);
    
    // 3. Fire the photo command!
    const tid = await publishDroneCommand(sn, 'camera_photo_take', {
      payload_index: "1-0-0"
    });

    res.json({
      success: true,
      message: `Force-Photo command sent to ${sn}`
    });
  } catch (error) {
    console.error('[API] Photo command error:', error);
    res.status(500).json({ 
      success: false,
      error: 'Failed to send photo command'
    });
  }
});
/**
 * POST /api/command/:sn/mission
 * Send waypoint mission command (requires operator or admin role)
 */
router.post('/command/:sn/mission', authMiddleware, requireRole('operator', 'admin'), async (req, res) => {
  const { sn } = req.params;
  const { waypoints } = req.body;

  if (!waypoints || !Array.isArray(waypoints) || waypoints.length === 0) {
    return res.status(400).json({ error: 'Waypoints array required' });
  }

  try {
    const commandData = { waypoints };
    const commandId = await db.saveCommand(sn, 'mission', commandData);

    if (!commandId) {
      return res.status(500).json({ error: 'Failed to save command' });
    }

    // NOTE: For true DJI Cloud API Waypoints, this eventually needs to be swapped 
    // to the `flighttask_ready` method and point to a `.kmz` file URL.
    // Leaving this as action 20 for now so your frontend doesn't break.
    const tid = await publishDroneCommand(sn, 'flight_control', {
      action: 20,  
      waypoints: waypoints
    });

    await db.updateCommandStatus(commandId, 'sent');

    res.json({
      success: true,
      commandId,
      tid,
      message: `Mission with ${waypoints.length} waypoints sent to ${sn}`,
      data: commandData
    });
  } catch (error) {
    console.error('[API] Mission command error:', error);
    res.status(500).json({ 
      success: false,
      error: 'Failed to send mission',
      details: error.message
    });
  }
});

/**
 * POST /api/telemetry/:sn (for testing - public)
 * Manually inject telemetry data
 */
router.post('/telemetry/:sn', async (req, res) => {
  const { sn } = req.params;
  const telemetryData = req.body;

  if (!sn) {
    return res.status(400).json({ error: 'Missing device serial number' });
  }

  if (!telemetryData || Object.keys(telemetryData).length === 0) {
    return res.status(400).json({ error: 'Missing telemetry data' });
  }

  await state.updateTelemetry(sn, telemetryData);

  res.json({
    success: true,
    message: 'Telemetry data updated',
    sn: sn,
    data: telemetryData
  });
});

/**
 * DELETE /api/telemetry (for testing - requires admin)
 * Clear all telemetry data
 */
router.delete('/telemetry', authMiddleware, requireRole('admin'), (req, res) => {
  state.clearAll();
  
  res.json({
    success: true,
    message: 'All telemetry data cleared'
  });
});

// Export router and setMqttClient function
module.exports = router;
module.exports.setMqttClient = setMqttClient;
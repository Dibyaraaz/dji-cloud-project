// backend/src/websocket.js
const WebSocket = require('ws');

let wss = null;
const clients = new Set();

function initWebSocket(server) {
  wss = new WebSocket.Server({ server, path: '/ws' });

  wss.on('connection', (ws, req) => {
    const clientIp = req.socket.remoteAddress;
    console.log('[ws] Client connected:', clientIp);
    clients.add(ws);

    ws.on('message', (message) => {
      try {
        const data = JSON.parse(message.toString());
        handleClientMessage(ws, data);
      } catch (error) {
        console.error('[ws] Invalid message:', error.message);
      }
    });

    ws.on('close', () => {
      console.log('[ws] Client disconnected:', clientIp);
      clients.delete(ws);
    });

    ws.on('error', (error) => {
      console.error('[ws] WebSocket error:', error.message);
      clients.delete(ws);
    });

    // Send welcome message
    ws.send(JSON.stringify({
      type: 'connected',
      message: 'Connected to DJI Cloud API WebSocket',
      timestamp: new Date().toISOString()
    }));
  });

  console.log('[ws] WebSocket server initialized on /ws');
  return wss;
}

function handleClientMessage(ws, data) {
  const { type, payload } = data;

  switch (type) {
    case 'ping':
      ws.send(JSON.stringify({ type: 'pong', timestamp: new Date().toISOString() }));
      break;
    
    case 'subscribe':
      // Client wants to subscribe to specific device
      ws.subscribedDevices = payload.devices || [];
      ws.send(JSON.stringify({ type: 'subscribed', devices: ws.subscribedDevices }));
      break;
    
    default:
      ws.send(JSON.stringify({ type: 'error', message: 'Unknown message type' }));
  }
}

function broadcastTelemetry(deviceSn, telemetryData) {
  if (!wss || clients.size === 0) return;

  const message = JSON.stringify({
    type: 'telemetry',
    deviceSn,
    data: telemetryData,
    timestamp: new Date().toISOString()
  });

  clients.forEach((ws) => {
    if (ws.readyState === WebSocket.OPEN) {
      // If client subscribed to specific devices, only send those
      if (!ws.subscribedDevices || ws.subscribedDevices.length === 0 || ws.subscribedDevices.includes(deviceSn)) {
        ws.send(message);
      }
    }
  });
}

function broadcastDeviceStatus(deviceSn, status) {
  if (!wss || clients.size === 0) return;

  const message = JSON.stringify({
    type: 'device_status',
    deviceSn,
    status,
    timestamp: new Date().toISOString()
  });

  clients.forEach((ws) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(message);
    }
  });
}

function broadcastEvent(deviceSn, eventType, eventData) {
  if (!wss || clients.size === 0) return;

  const message = JSON.stringify({
    type: 'event',
    deviceSn,
    eventType,
    data: eventData,
    timestamp: new Date().toISOString()
  });

  clients.forEach((ws) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(message);
    }
  });
}

function getConnectedClientsCount() {
  return clients.size;
}

module.exports = {
  initWebSocket,
  broadcastTelemetry,
  broadcastDeviceStatus,
  broadcastEvent,
  getConnectedClientsCount
};

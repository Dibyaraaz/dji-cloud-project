// backend/src/database.js
const mysql = require('mysql2/promise');

let pool = null;

async function initDatabase() {
  const maxRetries = 5;
  let retries = 0;
  
  while (retries < maxRetries) {
    try {
      pool = mysql.createPool({
        host: process.env.MYSQL_HOST || 'mysql',
        port: process.env.MYSQL_PORT || 3306,
        user: 'root',
        password: process.env.MYSQL_ROOT_PASSWORD || 'rootpass',
        database: process.env.MYSQL_DATABASE || 'dji_cloud',
        waitForConnections: true,
        connectionLimit: 10, 
        queueLimit: 0
      });

      const connection = await pool.getConnection();
      console.log('[db] MySQL connected');
      connection.release();
      return pool;
    } catch (error) {
      retries++;
      console.log(`[db] MySQL connection attempt ${retries}/${maxRetries} failed: ${error.message}`);
      if (retries < maxRetries) {
        console.log('[db] Retrying in 3 seconds...');
        await new Promise(resolve => setTimeout(resolve, 3000));
      } else {
        console.error('[db] MySQL connection failed after all retries');
        return null;
      }
    }
  }
}
async function saveDevice(deviceData) {
  if (!pool) return false;
  const { sn, type, model, firmware, metadata } = deviceData;
  
  try {
    await pool.execute(
      `INSERT INTO devices (sn, type, model, firmware, metadata)
       VALUES (?, ?, ?, ?, ?)
       ON DUPLICATE KEY UPDATE type=VALUES(type), model=VALUES(model), 
       firmware=VALUES(firmware), metadata=VALUES(metadata)`,
      [
        sn,
        type || 'unknown',
        model || null,        // Changed from just 'model'
        firmware || null,     // Changed from just 'firmware'
        metadata ? JSON.stringify(metadata) : null
      ]
    );
    return true;
  } catch (error) {
    console.error('[db] Error saving device:', error.message);
    return false;
  }
}

async function saveTelemetry(deviceSn, telemetryData) {
  if (!pool) return false;
  
  const { latitude, longitude, altitude, battery, speed, heading, verticalSpeed, satellites, timestamp } = telemetryData;
  
  try {
    await pool.execute(
      `INSERT INTO telemetry (device_sn, timestamp, latitude, longitude, altitude, battery, speed, heading, vertical_speed, satellites, raw_data)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [
        deviceSn,
        timestamp ? new Date(timestamp) : new Date(),
        latitude !== undefined ? latitude : null,
        longitude !== undefined ? longitude : null,
        altitude !== undefined ? altitude : null,
        battery !== undefined ? battery : null,
        speed !== undefined ? speed : null,
        heading !== undefined ? heading : null,
        verticalSpeed !== undefined ? verticalSpeed : null,
        satellites !== undefined ? satellites : null,
        JSON.stringify(telemetryData)
      ]
    );
    return true;
  } catch (error) {
    console.error('[db] Error saving telemetry:', error.message);
    return false;
  }
}

async function getDeviceHistory(deviceSn, limit = 100) {
  if (!pool) return [];
  try {
    const safeLimit = Math.min(parseInt(limit) || 100, 1000);
    const sql = `SELECT * FROM telemetry WHERE device_sn = ? ORDER BY timestamp DESC LIMIT ${safeLimit}`;
    const [rows] = await pool.query(sql, [deviceSn]);
    return rows;
  } catch (error) {
    console.error('[db] Error getting history:', error.message);
    return [];
  }
}
async function getUserByUsername(username) {
  if (!pool) return null;
  try {
    const [rows] = await pool.execute('SELECT * FROM users WHERE username = ?', [username]);
    return rows[0] || null;
  } catch (error) {
    console.error('[db] Error getting user:', error.message);
    return null;
  }
}

async function saveCommand(deviceSn, commandType, commandData) {
  if (!pool) return null;
  try {
    const [result] = await pool.execute(
      'INSERT INTO commands (device_sn, command_type, command_data) VALUES (?, ?, ?)',
      [deviceSn, commandType, commandData ? JSON.stringify(commandData) : null]
    );
    return result.insertId;
  } catch (error) {
    console.error('[db] Error saving command:', error.message);
    return null;
  }
}

async function updateCommandStatus(commandId, status) {
  if (!pool) return false;
  try {
    await pool.execute('UPDATE commands SET status = ? WHERE id = ?', [status, commandId]);
    return true;
  } catch (error) {
    console.error('[db] Error updating command:', error.message);
    return false;
  }
}

module.exports = {
  initDatabase,
  saveDevice,
  saveTelemetry,
  getDeviceHistory,
  getUserByUsername,
  saveCommand,
  updateCommandStatus
};

-- Minimal table for storing last-seen telemetry (optional).
CREATE TABLE IF NOT EXISTS telemetry_last (
  id INT AUTO_INCREMENT PRIMARY KEY,
  gateway_sn VARCHAR(64) NOT NULL,
  topic VARCHAR(256) NOT NULL,
  payload_json JSON NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_gateway_topic (gateway_sn, topic)
);

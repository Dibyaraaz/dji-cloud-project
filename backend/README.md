# DJI Pilot 2 → Cloud API Minimal Backend (Docker)

This project gives you a **working, minimal** backend stack you can run locally and connect to DJI Pilot 2
(used by Mavic 3 Enterprise series) via **Cloud Service → Other Platforms**.

It includes:
- EMQX (MQTT broker)
- MySQL + Redis (optional persistence; backend will run without using them heavily)
- Node.js backend that:
  - hosts a Pilot 2 login/connect page at `/pilot-login`
  - sets Pilot 2 cloud parameters via JSBridge (license verify → load thing → set information)
  - subscribes to DJI Cloud API MQTT topics and prints telemetry (and stores last-seen in memory)

> Why a web page? Pilot 2 connects to your platform by opening a URL you provide. From that page, you call DJI’s JSBridge APIs to
verify license, load the cloud component, and set the MQTT/server information.

## 0) Prereqs
- Docker + Docker Compose installed
- Your RC (running DJI Pilot 2) and your computer are on the same network (same Wi‑Fi / hotspot)

## 1) Configure
Copy `.env.example` to `.env` and fill in:

- `HOST_ADDR` = your computer’s LAN IP address (the RC must reach it)
- `DJI_APP_ID`, `DJI_APP_KEY`, `DJI_LICENSE` = from DJI Developer console
- optionally change `MQTT_USERNAME`/`MQTT_PASSWORD`

## 2) Start
```bash
docker compose up -d --build
```

Open:
- Backend page: `http://HOST_ADDR:6789/pilot-login`
- EMQX dashboard: `http://HOST_ADDR:18083` (user/pass from `.env`)

## 3) Create the MQTT user in EMQX (required if anonymous login disabled)
By default this project **disables anonymous** access (recommended).

In EMQX dashboard:
- Authentication → Built-in Database → Users
- Add user: `MQTT_USERNAME` / `MQTT_PASSWORD`

## 4) Connect from DJI Pilot 2
On the RC:
1. Open **DJI Pilot 2**
2. Go to **Cloud Service → Other Platforms**
3. Enter URL: `http://HOST_ADDR:6789/pilot-login`
4. Tap **Connect** (or Login/Authorize depending on version)

When it connects, you should see logs in:
```bash
docker logs -f cloud-backend
```

## What you should expect
- The web page calls JSBridge in the DJI-documented order:
  1) verify license
  2) load cloud component ("thing") which triggers MQTT connect
  3) on MQTT connected callback
  4) set cloud information (MQTT host/user/pass etc.)
- The backend subscribes to wildcard topics like `thing/product/+/osd` and prints messages.

## Notes / Common gotchas
- **Use your LAN IP**, not `localhost` or `127.0.0.1`.
- DJI Pilot 2 MQTT URL should be **tcp** (not websocket) unless you intentionally use WS.
- If you don't see messages, check:
  - firewall on your laptop (ports 6789 and 1883)
  - EMQX user exists and creds match
  - RC is on the same network

## Where to customize next
- `backend/src/mqtt.js` subscriptions & decoding
- `backend/src/routes/pilot.js` if you want custom login, token exchange, user binding, etc.

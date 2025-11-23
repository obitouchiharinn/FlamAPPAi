// Simple HTTP + WebSocket server for receiving frames and broadcasting to web clients
const express = require('express');
const http = require('http');
const cors = require('cors');
const bodyParser = require('body-parser');
const WebSocket = require('ws');

const HTTP_PORT = 9000; // starting port for HTTP upload server
const WS_PORT = 8081; // starting port for WebSocket server
const os = require('os');

function getLocalIPv4Addresses() {
  const nets = os.networkInterfaces();
  const results = [];
  for (const name of Object.keys(nets)) {
    for (const net of nets[name]) {
      if (net.family === 'IPv4' && !net.internal) {
        results.push(net.address);
      }
    }
  }
  return results;
}

// HTTP server to receive uploads
const app = express();
app.use(cors());
app.use(bodyParser.json({ limit: '10mb' }));

let latestFrame = null;
let usedHttpPort = null;
let usedWsPort = null;

app.post('/upload', (req, res) => {
  try {
    const { image } = req.body || {};
    if (!image || typeof image !== 'string') {
      return res.status(400).json({ success: false, error: 'Missing image (base64) in body' });
    }
    latestFrame = image;
    console.log('Received upload (image length):', image.length);
    // Broadcast to all WS clients
    broadcast({ type: 'frame', image });
    console.log('Broadcasted frame to WebSocket clients (if any)');
    return res.json({ success: true });
  } catch (e) {
    console.error('Upload error:', e);
    return res.status(500).json({ success: false, error: String(e) });
  }
});

const httpServer = http.createServer(app);

// Try to bind HTTP server to a free port starting at HTTP_PORT
function startHttpServer(startPort, maxRetries = 10) {
  return new Promise((resolve, reject) => {
    let port = startPort;
    function tryListen() {
      httpServer.listen(port, '0.0.0.0', () => {
        console.log(`HTTP upload server listening on http://0.0.0.0:${port}`);
        console.log('POST base64 frames to /upload with JSON body: { "image": "data:image/png;base64,..." }');
        usedHttpPort = port;
        const localIPs = getLocalIPv4Addresses();
        console.log('Server accessible at:');
        console.log(`  - http://localhost:${port}`);
        for (const ip of localIPs) {
          console.log(`  - http://${ip}:${port}`);
        }
        resolve(port);
      }).on('error', (err) => {
        if (err && err.code === 'EADDRINUSE') {
          console.warn(`Port ${port} in use, trying next port...`);
          port++;
          if (port > startPort + maxRetries) {
            reject(new Error('No available ports for HTTP server'));
          } else {
            setTimeout(tryListen, 200);
          }
        } else {
          reject(err);
        }
      });
    }
    tryListen();
  });
}

startHttpServer(HTTP_PORT).catch(err => {
  console.error('Failed to start HTTP server:', err);
  process.exit(1);
});

// Expose a simple info endpoint so remote clients can discover active ports
app.get('/info', (req, res) => {
  const ips = getLocalIPv4Addresses();
  return res.json({ httpPort: usedHttpPort, wsPort: usedWsPort, ips });
});

// WebSocket server to push frames to viewers
// Try to bind WebSocket server to a free port starting at WS_PORT
function startWebSocketServer(startPort, maxRetries = 10) {
  return new Promise((resolve, reject) => {
    let port = startPort;
    function tryStart() {
      try {
        const server = new WebSocket.Server({ port: port, host: '0.0.0.0' });
        server.on('listening', () => {
          console.log(`WebSocket server listening on ws://0.0.0.0:${port}`);
          usedWsPort = port;
          resolve({ server, port });
        });
        server.on('error', (err) => {
          if (err && err.code === 'EADDRINUSE') {
            console.warn(`WS port ${port} in use, trying next port...`);
            port++;
            if (port > startPort + maxRetries) {
              reject(new Error('No available ports for WebSocket server'));
            } else {
              setTimeout(tryStart, 200);
            }
          } else {
            reject(err);
          }
        });
      } catch (err) {
        if (err && err.code === 'EADDRINUSE') {
          console.warn(`WS port ${port} in use (sync error), trying next port...`);
          port++;
          if (port > startPort + maxRetries) {
            reject(new Error('No available ports for WebSocket server'));
          } else {
            setTimeout(tryStart, 200);
          }
        } else {
          reject(err);
        }
      }
    }
    tryStart();
  });
}



// Use global.wss in broadcast and connection handling below

function broadcast(payload) {
  const data = JSON.stringify(payload);
  const server = global.wss;
  if (!server || !server.clients) return;
  server.clients.forEach(client => {
    if (client.readyState === WebSocket.OPEN) {
      client.send(data);
    }
  });
}

// Attach connection handler once websocket server is ready
// If the server was already started above, this will be executed in the promise resolution.
// Otherwise, this will be a no-op until startWebSocketServer resolves.
startWebSocketServer(WS_PORT).then(({ server: wssRef, port }) => {
  // ensure global.wss is set
  global.wss = wssRef;

  wssRef.on('connection', ws => {
    console.log('WebSocket client connected');
    ws.send(JSON.stringify({ type: 'hello', message: 'connected' }));
    // If we have the last frame, send it immediately
    if (latestFrame) {
      ws.send(JSON.stringify({ type: 'frame', image: latestFrame }));
    }

    ws.on('message', msg => {
      try {
        const data = JSON.parse(String(msg));
        if (data.type === 'ping') {
          ws.send(JSON.stringify({ type: 'pong', ts: Date.now() }));
        }
      } catch (e) {
        // ignore
      }
    });

    ws.on('close', () => console.log('WebSocket client disconnected'));
  });
}).catch(err => {
  // already handled above, but keep guard
});

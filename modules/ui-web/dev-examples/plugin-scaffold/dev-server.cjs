#!/usr/bin/env node
/**
 * dev-server.cjs — tiny HTTP server for the scaffold plugin source.
 *
 * The `.cjs` extension is required: modules/ui-web/package.json is `"type": "module"`, so a `.js`
 * file here would be parsed as ESM and `require` would throw `require is not defined`.
 *
 * Tempdoc 508 §6.2 — one-command dev server. Run:
 *   node dev-server.cjs
 * then open JustSearch and load `http://localhost:3001/plugin.js` via
 * Settings → Plugins → Load from URL.
 *
 * Re-saving plugin.js while JustSearch is running triggers hot-reload
 * if hot-reload is wired to your plugin directory; otherwise click
 * Load again to re-install.
 */

const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 3001;
const HOST = '127.0.0.1';
const ROOT = __dirname;

const MIME = {
  '.js': 'application/javascript',
  '.json': 'application/json',
  '.css': 'text/css',
  '.html': 'text/html',
  '.txt': 'text/plain',
  '.md': 'text/markdown',
};

const server = http.createServer((req, res) => {
  // CORS: JustSearch runs on a different origin in browser dev
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Cache-Control', 'no-store');

  const urlPath = (req.url || '/').split('?')[0];
  const safePath = path.normalize(urlPath).replace(/^[\/\\]+/, '');
  const filePath = path.join(ROOT, safePath || 'plugin.js');

  // Prevent directory traversal
  if (!filePath.startsWith(ROOT)) {
    res.statusCode = 403;
    res.end('Forbidden');
    return;
  }

  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.statusCode = 404;
      res.end(`Not found: ${urlPath}`);
      return;
    }
    const ext = path.extname(filePath);
    res.setHeader('Content-Type', MIME[ext] || 'application/octet-stream');
    res.statusCode = 200;
    res.end(data);
  });
});

server.listen(PORT, HOST, () => {
  console.log(`[plugin-scaffold] Serving ${ROOT}`);
  console.log(`[plugin-scaffold] Plugin URL: http://${HOST}:${PORT}/plugin.js`);
  console.log('[plugin-scaffold] In JustSearch: Settings -> Plugins -> Load from URL');
});

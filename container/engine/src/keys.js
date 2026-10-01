'use strict';

const fs = require('fs');
const path = require('path');

const DEFAULT_PRIVATE_KEY_PATH = path.resolve(__dirname, '..', '..', '..', 'keys', 'ota-private.pem');

function loadPrivateKey(p) {
  const fp = p || process.env.LOBOS_OTA_PRIVATE_KEY_PATH || DEFAULT_PRIVATE_KEY_PATH;
  return fs.readFileSync(fp, 'utf8');
}

module.exports = { DEFAULT_PRIVATE_KEY_PATH, loadPrivateKey };

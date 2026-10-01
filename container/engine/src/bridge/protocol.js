'use strict';

const PROTOCOL_VERSION = 1;
const MIN_PROTOCOL = 1;

const ERROR_CODES = {
  PARSE_ERROR: -32700,
  INVALID_REQUEST: -32600,
  METHOD_NOT_FOUND: -32601,
  INVALID_PARAMS: -32602,
  INTERNAL_ERROR: -32603,
  ERR_RUNTIME: -32000,
  ERR_CAPABILITY_MISSING: -32001,
  ERR_TIMEOUT: -32002,
  ERR_SESSION_MISSING: -32004,
  ERR_POLICY_DENIED: -32005,
};

function request(id, method, params) {
  return { jsonrpc: '2.0', id, method, params: params || {} };
}
function response(id, result) {
  return { jsonrpc: '2.0', id, result };
}
function error(id, code, message, data) {
  const e = { jsonrpc: '2.0', id, error: { code, message } };
  if (data !== undefined) e.error.data = data;
  return e;
}
function notification(method, params) {
  return { jsonrpc: '2.0', method, params: params || {} };
}
function parse(str) {
  return JSON.parse(str);
}

function handshakeRequest(id, requires, program, token) {
  return request(id, 'bridge.handshake', {
    protocol: PROTOCOL_VERSION,
    program: program || null,
    requires: requires || [],
    token: token || null,
  });
}

function negotiateGroups(requires, availableGroups) {
  const granted = (requires || []).filter((r) => availableGroups.includes(r));
  const missing = (requires || []).filter((r) => !availableGroups.includes(r));
  return { granted, missing };
}

module.exports = {
  PROTOCOL_VERSION,
  MIN_PROTOCOL,
  ERROR_CODES,
  request,
  response,
  error,
  notification,
  parse,
  handshakeRequest,
  negotiateGroups,
};

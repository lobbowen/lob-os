'use strict';

const fs = require('fs');
const path = require('path');
const { extractZip } = require('./zip');
const { sha256, verifyManifest } = require('./verify');
const { isNewer } = require('./program-version');

class OtaEngine {
  constructor({ baseDir, httpGet, publicKeyPem, capabilities, runtime, protocol, log, allowDowngrade }) {
    this.baseDir = baseDir;
    this.httpGet = httpGet;
    this.publicKeyPem = publicKeyPem;
    this.capabilities = capabilities || [];
    this.runtime = runtime || { node: process.version };
    this.shellProtocol = Number(protocol || 0);
    this.log = log || (() => {});
    this.allowDowngrade = allowDowngrade === true;
    this.programDir = path.join(baseDir, 'programs', 'console');
    this.currentPointer = path.join(this.programDir, 'CURRENT');
  }

  currentVersion() {
    try { return fs.readFileSync(this.currentPointer, 'utf8').trim(); } catch (_e) { return null; }
  }

  _setPointer(v) {
    fs.mkdirSync(this.programDir, { recursive: true });
    const tmp = path.join(this.programDir, 'CURRENT.tmp');
    fs.writeFileSync(tmp, v);
    fs.renameSync(tmp, this.currentPointer);
  }

  installedVersions() {
    try {
      return fs.readdirSync(this.programDir)
        .filter((d) => d !== 'CURRENT' && fs.statSync(path.join(this.programDir, d)).isDirectory());
    } catch (_e) { return []; }
  }

  verifyPackage(zipBuf, manifest) {
    if (sha256(zipBuf) !== manifest.sha256) return { ok: false, reason: 'sha256-mismatch' };
    const tmp = path.join(this.baseDir, '.ota-verify-' + process.pid + '-' + Date.now());
    try {
      extractZip(zipBuf, tmp);
      const kjPath = path.join(tmp, 'program', manifest.version, 'program-manifest.json');
      if (!fs.existsSync(kjPath)) return { ok: false, reason: 'no-program-manifest' };
      const manifestJson = JSON.parse(fs.readFileSync(kjPath, 'utf8'));
      if (!verifyManifest(this.publicKeyPem, manifestJson, manifestJson.signature))
        return { ok: false, reason: 'signature-invalid' };
      if (!this._nodeSatisfied(manifestJson.engines && manifestJson.engines.node))
        return { ok: false, reason: 'node-engine-unsatisfied' };
      const missing = (manifestJson.requires || []).filter((r) => !this.capabilities.includes(r));
      if (missing.length) return { ok: false, reason: 'capability-missing', missing };
      const reqProto = Number(manifestJson.requiresProtocol || 0);
      if (reqProto > this.shellProtocol) {
        return { ok: false, reason: 'protocol-unsatisfied', required: reqProto, have: this.shellProtocol };
      }
      return { ok: true, manifestJson };
    } catch (e) {
      return { ok: false, reason: 'extract-failed', error: String(e && e.message) };
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true });
    }
  }

  _nodeSatisfied(range) {
    if (!range) return true;
    const m = /v?(\d+)(?:\.(\d+))?(?:\.(\d+))?/.exec(this.runtime.node || process.version);
    if (!m) return false;
    const cur = [+m[1], +(m[2] || 0), +(m[3] || 0)];
    const cmp = (a, b) => { for (let i = 0; i < 3; i += 1) { if (a[i] !== b[i]) return a[i] - b[i]; } return 0; };
    const parse = (s) => { const x = /v?(\d+)(?:\.(\d+))?(?:\.(\d+))?/.exec(s); return [+x[1], +(x[2] || 0), +(x[3] || 0)]; };
    let ok = true;
    for (const p of range.split(/\s+/).filter(Boolean)) {
      const mm = /^([<>]=?|=)?\s*v?(\d+)(?:\.(\d+))?(?:\.(\d+))?$/.exec(p);
      if (!mm) continue;
      const op = mm[1] || '=';
      const t = parse(mm[2] + '.' + (mm[3] || 0) + '.' + (mm[4] || 0));
      const c = cmp(cur, t);
      if (op === '>=') ok = ok && c >= 0;
      else if (op === '>') ok = ok && c > 0;
      else if (op === '<=') ok = ok && c <= 0;
      else if (op === '<') ok = ok && c < 0;
      else if (op === '=') ok = ok && c === 0;
    }
    return ok;
  }

  apply(version, zipBuf) {
    const dest = path.join(this.programDir, version);
    const tmp = dest + '.tmp-' + process.pid + '-' + Date.now();
    fs.rmSync(tmp, { recursive: true, force: true });
    try {
      extractZip(zipBuf, tmp);
      const inner = path.join(tmp, 'program', version);
      if (!fs.existsSync(inner)) throw new Error('包内缺少 program/' + version + ' 目录');
      const aside = fs.existsSync(dest) ? dest + '.replaced-' + Date.now() : null;
      if (aside) fs.renameSync(dest, aside);
      try {
        fs.renameSync(inner, dest);
      } catch (e) {
        if (aside) fs.renameSync(aside, dest);
        throw e;
      }
      if (aside) fs.rmSync(aside, { recursive: true, force: true });
    } catch (e) {
      fs.rmSync(tmp, { recursive: true, force: true });
      throw e;
    }
    fs.rmSync(tmp, { recursive: true, force: true });
    this._setPointer(version);
    this.log('ota: 已切换到内核 ' + version);
    return dest;
  }

  rollback() {
    const cur = this.currentVersion();
    const prev = this.installedVersions().filter((v) => v !== cur).sort().pop();
    if (prev) { this._setPointer(prev); this.log('ota: 回滚到 ' + prev); return prev; }
    return null;
  }

  checkUpdate(manifest) {
    const cur = this.currentVersion();
    const missing = [];
    if (!manifest || typeof manifest !== 'object') missing.push('manifest');
    else {
      if (!(Number(manifest.expiresEpochMs) > 0)) missing.push('expiresEpochMs');
      if (!manifest.sha256) missing.push('sha256');
      if (!manifest.signature) missing.push('signature');
    }
    if (missing.length) {
      return {
        available: false,
        reason: 'manifest-missing-fields',
        missing,
        message: 'manifest 必填字段缺失（' + missing.join(',') + '）—— 拒绝安装',
      };
    }
    if (Number(manifest.expiresEpochMs) < Date.now()) {
      return { available: false, reason: 'manifest-expired', message: 'manifest 已过期，拒绝继续比版本' };
    }
    if (isNewer(manifest.version, cur)) {
      return { available: true, version: manifest.version, manifest, downgrade: false };
    }
    if (cur && manifest.version === cur) {
      return { available: false, reason: 'up-to-date', message: '已是最新 ' + cur };
    }
    if (!this.allowDowngrade) {
      return {
        available: false,
        reason: 'downgrade-not-allowed',
        message: '远端 ' + manifest.version + ' 低于本地 ' + cur + ' —— 降级需显式策略 allowDowngrade=true',
      };
    }
    this.log('ota: 显式策略允许降级 ' + cur + ' → ' + manifest.version);
    return { available: true, version: manifest.version, manifest, downgrade: true };
  }
}

module.exports = { OtaEngine };

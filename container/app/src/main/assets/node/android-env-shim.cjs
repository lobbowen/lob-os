'use strict';

try {
  const os = require('node:os');
  const real = os.cpus;
  if (typeof real === 'function') {
    let sample = null;
    try { sample = real.call(os); } catch (_) { sample = null; }
    if (Array.isArray(sample) && sample.length === 0) {
      let n = 1;
      try {
        if (typeof os.availableParallelism === 'function') n = os.availableParallelism() || 1;
      } catch (_) { n = 1; }
      os.cpus = function () {
        const out = [];
        for (let i = 0; i < n; i += 1) {
          out.push({
            model: 'android',
            speed: 0,
            times: { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 },
          });
        }
        return out;
      };
    }
  }
} catch (_) {   }

#!/usr/bin/env node
const t = require("../../scripts/component-sources.json");
process.stdout.write(Object.keys(t.sources || {}).join("\n") + "\n");

#!/usr/bin/env bash
set -euo pipefail
# Dev Container 的 Node.js Feature 提供 Node/npm；不使用 sudo 修改全局包。
npm install --global pnpm@12.18.0

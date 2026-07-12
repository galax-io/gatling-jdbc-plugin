#!/usr/bin/env bash
#
# install-hooks.sh — enable this repo's shared scalafmt pre-commit git hook.
# Points core.hooksPath at the tracked .githooks/ dir. Idempotent; run once per clone.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
chmod +x .githooks/pre-commit 2>/dev/null || true
git config core.hooksPath .githooks

echo "git hook enabled — core.hooksPath -> $(git config --get core.hooksPath)"
echo "  pre-commit: sbt scalafmtAll scalafmtSbt (+ re-stage)"
echo "  bypass:     SKIP_SCALAFMT=1  (or git commit --no-verify)"

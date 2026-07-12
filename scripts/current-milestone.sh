#!/usr/bin/env bash
#
# current-milestone.sh — print the CURRENT release milestone as "<number>\t<title>".
# Prints nothing (exit 0) when no version milestone is open.
#
# "Current" = the single open milestone whose title starts with `vX.Y.0` (lowest
# semver if several are open). Single source of truth for "which milestone is
# current" — shared by scripts/check-linkage.sh and .claude/hooks/milestone-guard.sh.
#
# Env: REPO=owner/name overrides the repo (default: gh repo view of the checkout).
# Exit: 0 ok (may print empty) | 2 cannot determine repo.
set -uo pipefail

REPO="${REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)}"
[ -n "$REPO" ] || { echo "current-milestone: cannot determine repo — set REPO=owner/name" >&2; exit 2; }

gh api "repos/$REPO/milestones?state=open&per_page=100" --jq '
  [ .[]
    | select(.title | test("^v[0-9]+\\.[0-9]+\\.0"))
    | . + { _k: ((.title | capture("^v(?<a>[0-9]+)\\.(?<b>[0-9]+)")) | (.a|tonumber) * 100000 + (.b|tonumber)) }
  ]
  | sort_by(._k) | (.[0] // null)
  | if . == null then empty else "\(.number)\t\(.title)" end'

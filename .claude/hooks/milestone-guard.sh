#!/usr/bin/env bash
#
# milestone-guard.sh — PreToolUse(Bash) hook.
# Milestone *assignments* of an issue/PR must target the CURRENT release milestone
# (the single open `vX.Y.0 …` milestone). Blocks scattering work into thematic or
# other-version milestones. Read-only `gh` calls and any command without a milestone
# assignment pass untouched with no network call (~0 tokens).
#
# "Current" = lowest-semver OPEN milestone whose title starts with `vX.Y.0`
# (same definition check-linkage.sh uses). Keep it in sync with that script.
#
# Bypass a deliberate backlog/deferral move by prefixing the command:
#   MILESTONE_GUARD_OFF=1 gh issue edit 42 --milestone "1d — …"
set -uo pipefail

input=$(cat)
cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // ""' 2>/dev/null || true)
[ -n "$cmd" ] || exit 0

# Fast path: only gh commands that mention a milestone are ever in scope.
case "$cmd" in *"gh "*) ;; *) exit 0 ;; esac
case "$cmd" in *milestone*) ;; *) exit 0 ;; esac
# Explicit opt-out for intentional off-milestone moves.
case "$cmd" in *MILESTONE_GUARD_OFF=1*) exit 0 ;; esac

block() { printf 'BLOCKED by milestone-guard:\n%s\n' "$1" >&2; exit 2; }

# --- Is this a milestone *assignment* (a write), not a read/list? -------------
is_write=0
# gh issue|pr edit|create ... --milestone <x>
if printf '%s' "$cmd" | grep -qE '\bgh[[:space:]]+(issue|pr)[[:space:]]+(edit|create)\b' \
   && printf '%s' "$cmd" | grep -qE -- '--milestone(=|[[:space:]])'; then
  is_write=1
fi
# gh api writing a milestone= field (-f/-F/--field/--raw-field milestone=…) or an
# explicit write method carrying a milestone= param. Query strings (?/&milestone=)
# are reads and never match the field-flag form.
if printf '%s' "$cmd" | grep -qE '\bgh[[:space:]]+api\b'; then
  if printf '%s' "$cmd" | grep -qE -- '(-f|-F|--field|--raw-field)[[:space:]]+milestone=' \
     || { printf '%s' "$cmd" | grep -qE -- '(--method|-X)[[:space:]]+(PATCH|POST|PUT)' \
          && printf '%s' "$cmd" | grep -qE 'milestone=' ; }; then
    is_write=1
  fi
fi
[ "$is_write" = 1 ] || exit 0

# Clearing a milestone is never a scatter.
printf '%s' "$cmd" | grep -qE -- '--remove-milestone' && exit 0

# --- Extract the assigned milestone value -------------------------------------
val=""
# api field form: milestone=VALUE (a numeric id)
val=$(printf '%s' "$cmd" | grep -oE 'milestone=[^ ]+' | head -1 | sed 's/^milestone=//')
# --milestone=VALUE
if [ -z "$val" ]; then
  val=$(printf '%s' "$cmd" | grep -oE -- '--milestone=[^ ]+' | head -1 | sed 's/^--milestone=//')
fi
# --milestone VALUE  (quoted title, or a bare token)
if [ -z "$val" ]; then
  val=$(printf '%s' "$cmd" | sed -nE 's/.*--milestone[[:space:]]+"([^"]*)".*/\1/p' | head -1)
  [ -z "$val" ] && val=$(printf '%s' "$cmd" | sed -nE "s/.*--milestone[[:space:]]+'([^']*)'.*/\1/p" | head -1)
  [ -z "$val" ] && val=$(printf '%s' "$cmd" | sed -nE 's/.*--milestone[[:space:]]+([^ ]+).*/\1/p' | head -1)
fi
# strip a stray leading/trailing quote if one survived
val=$(printf '%s' "$val" | sed -E 's/^["'"'"']//; s/["'"'"']$//')
# Empty target = clearing the milestone; not a scatter.
[ -n "$val" ] || exit 0

# --- Resolve the current release milestone ------------------------------------
REPO="${REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)}"
[ -n "$REPO" ] || block "cannot determine repo to verify milestone — set REPO=owner/name, or bypass with MILESTONE_GUARD_OFF=1"

cur=$(gh api "repos/$REPO/milestones?state=open&per_page=100" --jq '
  [ .[]
    | select(.title | test("^v[0-9]+\\.[0-9]+\\.0"))
    | . + { _k: ((.title | capture("^v(?<a>[0-9]+)\\.(?<b>[0-9]+)")) | (.a|tonumber) * 100000 + (.b|tonumber)) }
  ]
  | sort_by(._k) | .[0] // empty
  | if . == "" then "" else "\(.number)\t\(.title)" end' 2>/dev/null || true)

[ -n "$cur" ] || block "no open version milestone (vX.Y.0) to assign into — create the release milestone first, or bypass with MILESTONE_GUARD_OFF=1.
  target: $val"

cur_num=$(printf '%s' "$cur" | cut -f1)
cur_title=$(printf '%s' "$cur" | cut -f2)
cur_ver=$(printf '%s' "$cur_title" | grep -oE 'v[0-9]+\.[0-9]+\.[0-9]+' | head -1)

# --- Compare target to current ------------------------------------------------
ok=0
if printf '%s' "$val" | grep -qE '^[0-9]+$'; then
  [ "$val" = "$cur_num" ] && ok=1                       # assigned by milestone number
else
  vtok=$(printf '%s' "$val" | grep -oE 'v[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  { [ -n "$vtok" ] && [ "$vtok" = "$cur_ver" ]; } && ok=1   # title carries the current version
  [ "$val" = "$cur_title" ] && ok=1                    # exact title match
fi
[ "$ok" = 1 ] && exit 0

block "issue/PR milestone must be the current release milestone.
  current : #$cur_num  $cur_title
  target  : $val
Assign to #$cur_num (its number, title, or $cur_ver). To defer to the backlog on purpose, prefix: MILESTONE_GUARD_OFF=1 <cmd>"

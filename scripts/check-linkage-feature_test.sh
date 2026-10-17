#!/usr/bin/env bash
#
#
# check-linkage-feature_test.sh — tests for the feature rule of scripts/check-linkage.sh
# (AGENTS.md "Feature milestones"): a PR that writes `Part of <backlog>#N` for an issue
# whose milestone description has the line `Release: business` links a feature, so the
# milestone of that issue need not match the PR's own; the PR still needs its milestone.
# Plus regressions for the behaviour the script had before (Closes #N).
#
# Hermetic: a stub `gh` answers from fixture files; no network, no credentials.
# (scripts/check-linkage_test.sh is the bootstrap template's suite for a different
# script and is not run against this one.)

# shellcheck disable=SC2046
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/check-linkage.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

command -v jq >/dev/null 2>&1 || { echo "error: 'jq' not found on PATH" >&2; exit 2; }

pass=0; fail=0
ok()  { printf '  ✓ %s\n' "$1"; pass=$((pass + 1)); }
bad() { printf '  ✗ %s\n     %s\n' "$1" "$2"; fail=$((fail + 1)); }
check() { # description, condition-exit-code, detail
  if [ "$2" -eq 0 ]; then ok "$1"; else bad "$1" "$3"; fi
}

bin="$tmp/bin"; mkdir -p "$bin"
cat > "$bin/gh" <<'STUB'
#!/usr/bin/env bash
fx="${FIXTURES:?}"
unexpected() { echo "gh $*" >> "$fx/unexpected.log"; echo "stub gh: unexpected call: gh $*" >&2; exit 99; }
all="$*"
cmd="${1:-}"; [ $# -gt 0 ] && shift
expr=""; paginate=0; repo=""; positional=()
while [ $# -gt 0 ]; do
  case "$1" in
    --jq|-q)            shift; expr="${1:-}" ;;
    --paginate|--slurp) paginate=1 ;;
    --repo)             shift; repo="${1:-}" ;;
    --json)             shift ;;
    *)                  positional+=("$1") ;;
  esac
  shift
done
reply() { # file — the fixture, filtered through the --jq/-q expression when given
  if [ -n "$expr" ]; then jq -r "$expr" "$1"; else cat "$1"; fi
}
case "$cmd" in
  api)
    path="${positional[0]:-}"
    case "$path" in
      "repos/$REPO/milestones?state=open&per_page=100")
        echo '[{"number":8,"title":"v0.8.0"}]' | jq -r "${expr:-.}" ;;
      "repos/$REPO/milestones?state=all&per_page=100")
        echo '[{"number":7,"title":"v0.7.0"},{"number":8,"title":"v0.8.0"}]' ;;
      "repos/$REPO")
        echo '{"default_branch":"main"}' | jq -r "${expr:-.}" ;;
      "repos/$REPO/milestones/8")
        echo '{"title":"v0.8.0","state":"open"}' ;;
      "repos/$REPO/issues?milestone=8&state=all&per_page=100")
        [ "$paginate" = 1 ] || unexpected "$all"
        jq -sc '[.]' "$fx/items.ndjson" ;;   # --paginate --slurp: an array of pages
      *) unexpected "$all" ;;
    esac ;;
  pr)
    [ "${positional[0]:-}" = view ] && [ "$repo" = "$REPO" ] && [ -f "$fx/pr-${positional[1]:-}.json" ] || unexpected "$all"
    reply "$fx/pr-${positional[1]}.json" ;;
  issue)
    f="$fx/issue-${repo//\//_}-${positional[1]:-}.json"
    [ "${positional[0]:-}" = view ] && [ -f "$f" ] || unexpected "$all"
    reply "$f" ;;
  *) unexpected "$all" ;;
esac
STUB
chmod +x "$bin/gh"

REPO=galax-io/test
BACKLOG=galax-io/backlog
BUSINESS='Release: business'
new_case() { fx="$tmp/fx-$1"; mkdir -p "$fx"; : > "$fx/items.ndjson"; }
issue_in() { # owner/repo, number, milestone title, [milestone description]
  jq -nc --arg ms "$3" --arg desc "${4:-}" '{milestone: (if $ms == "" then null else {title: $ms, description: $desc} end)}' \
    > "$fx/issue-${1//\//_}-$2.json"
}
add_issue() { # number, state
  printf '{"number":%s,"state":"%s"}\n' "$1" "$2" >> "$fx/items.ndjson"
  issue_in "$REPO" "$1" v0.8.0
}
pr_json() { # number, state, closing issue number or "", body, milestone title or ""
  jq -nc --argjson n "$1" --arg state "$2" --arg c "$3" --arg body "$4" --arg ms "$5" '{
    number: $n, title: "PR \($n)", state: $state, body: $body,
    milestone: (if $ms == "" then null else {title: $ms} end),
    closingIssuesReferences: [$c | select(. != "") | {number: tonumber}]}' > "$fx/pr-$1.json"
}
add_pr() { # number, state, closing issue number or "", [body] — a PR in v0.8.0
  local rest=closed
  [ "$2" = OPEN ] && rest=open
  printf '{"number":%s,"state":"%s","pull_request":{}}\n' "$1" "$rest" >> "$fx/items.ndjson"
  pr_json "$1" "$2" "$3" "${4:-}" v0.8.0
}
run() {
  out="$(PATH="$bin:$PATH" REPO="$REPO" BACKLOG_REPO="$BACKLOG" FIXTURES="$fx" bash "$script" "$@" 2>&1)"; rc=$?
}
has() { grep -qF -- "$1" <<<"$out"; }
only_known_calls() { [ ! -s "$fx/unexpected.log" ]; }

echo "check-linkage.sh (feature rule)"

# 0. regression: Closes #N in the same milestone passes, in another fails
new_case gate-closes
pr_json 3 OPEN 1 "Closes #1" v0.8.0
issue_in "$REPO" 1 v0.8.0
run --pr 3
check "gate: closing an issue of the same milestone passes" $([ "$rc" -eq 0 ] && echo 0 || echo 1) "rc=$rc out=$out"
new_case gate-closes-other
pr_json 3 OPEN 1 "Closes #1" v0.8.0
issue_in "$REPO" 1 v0.7.0
run --pr 3
check "gate: closing an issue of another milestone fails" $([ "$rc" -eq 1 ] && echo 0 || echo 1) "rc=$rc out=$out"

# 1. gate: Part of a backlog feature passes whatever the feature's milestone
new_case gate-feature
pr_json 3 OPEN "" "Part of $BACKLOG#30" v0.8.0
issue_in "$BACKLOG" 30 'M1 — first run' "$BUSINESS"
run --pr 3
check "gate: Part of a backlog feature in a business milestone passes" $([ "$rc" -eq 0 ] && echo 0 || echo 1) "rc=$rc out=$out"
check "gate: it names the business milestone" $(has "✓ links $BACKLOG#30 (feature of business milestone \"M1 — first run\")" && echo 0 || echo 1) "out=$out"
check "gate: the script made only the calls the stub knows" $(only_known_calls && echo 0 || echo 1) "$(cat "$fx/unexpected.log" 2>&1)"

# 2. gate: the marker among other lines of the description, any case
new_case gate-feature-multiline
pr_json 3 OPEN "" "Part of $BACKLOG#30" v0.8.0
issue_in "$BACKLOG" 30 'M1 — first run' $'First real run.\nrelease: Business\nDue: autumn'
run --pr 3
check "gate: the marker on its own line of a longer description counts" $(has '(feature of business milestone' && echo 0 || echo 1) "out=$out"

# 3. gate: a backlog issue in an ordinary milestone is not a feature
new_case gate-backlog-plain
pr_json 3 OPEN "" "Part of $BACKLOG#31" v0.8.0
issue_in "$BACKLOG" 31 'v0.7.0' 'Tooling of the backlog itself.'
run --pr 3
check "gate: an ordinary backlog milestone is not a feature" $(has 'feature of business' && echo 1 || echo 0) "out=$out"

# 4. gate: the marker counts only in the backlog repository
new_case gate-marker-elsewhere
pr_json 3 OPEN "" "Part of $REPO#1" v0.8.0
issue_in "$REPO" 1 'M1 — first run' "$BUSINESS"
run --pr 3
check "gate: the marker in another repository does not make a feature" $(has 'feature of business' && echo 1 || echo 0) "out=$out"

# 5. gate: a backlog issue without a milestone is not a feature
new_case gate-feature-no-milestone
pr_json 3 OPEN "" "Part of $BACKLOG#32" v0.8.0
issue_in "$BACKLOG" 32 ""
run --pr 3
check "gate: a backlog issue without a milestone is not a feature" $(has 'feature of business' && echo 1 || echo 0) "out=$out"

# 6. gate: the PR still needs its own milestone
new_case gate-feature-no-pr-milestone
pr_json 3 OPEN "" "Part of $BACKLOG#30" ""
issue_in "$BACKLOG" 30 'M1 — first run' "$BUSINESS"
run --pr 3
check "gate: a feature link does not replace the PR's own milestone" $([ "$rc" -eq 1 ] && echo 0 || echo 1) "rc=$rc out=$out"
check "gate: it says to assign one" $(has '✗ no milestone' && echo 0 || echo 1) "out=$out"
check "gate: the feature link is still reported" $(has "✓ links $BACKLOG#30 (feature of business milestone" && echo 0 || echo 1) "out=$out"

# 7. audit: a PR of the milestone that links a backlog feature is listed, no warning
new_case audit-feature
add_issue 1 open
add_pr 2 OPEN "" "Part of $BACKLOG#30"
add_pr 4 OPEN 1
issue_in "$BACKLOG" 30 'M1 — first run' "$BUSINESS"
run
check "audit: linking a backlog feature passes the audit" $([ "$rc" -eq 0 ] && echo 0 || echo 1) "rc=$rc out=$out"
check "audit: the PR is listed with its feature" $(has "✓ PR #2 (OPEN) → part of feature $BACKLOG#30" && echo 0 || echo 1) "out=$out"
check "audit: it is not reported as closing no issue" $(has '! PR #2' && echo 1 || echo 0) "out=$out"

# 8. tag: a merged PR of a feature does not block the tag
new_case tag-feature
add_issue 1 closed
add_pr 2 MERGED 1
add_pr 3 MERGED "" "Part of $BACKLOG#30"
issue_in "$BACKLOG" 30 'M1 — first run' "$BUSINESS"
run --tag
check "tag: a merged PR that links a feature does not block the tag" $([ "$rc" -eq 0 ] && echo 0 || echo 1) "rc=$rc out=$out"
check "tag: the milestone is tag-ready" $(has 'Milestone #8 is tag-ready.' && echo 0 || echo 1) "out=$out"

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]

#!/usr/bin/env bash
# Sizes are exact here too: 4 KiB in the workspace, 2 KiB more from an extra path, and a 1 MB budget.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

dir="$(mktemp -d)"; mkdir -p "$dir/src"
dd if=/dev/zero of="$dir/src/blob.bin" bs=1024 count=4 2>/dev/null
printf '{"maxMb": "0"}' >"$dir/input.json"
run "$dir" >"$dir.run.log" 2>&1 || fail "run failed: $(cat "$dir.run.log")"
[ "$(status "$dir")" = "success" ] || fail "not a success: $(cat "$dir/output.json")"
[ "$(output "$dir" files)" = "1" ] || fail "file count: $(cat "$dir/output.json")"
[ "$(output "$dir" bytes)" = "4096" ] || fail "bytes: $(cat "$dir/output.json")"
[ -f "$dir/out/disk/index.html" ] || fail "no report"
grep -q "src" "$dir/out/disk/index.html" || fail "the directory breakdown is missing"

# extra paths are measured too
extra="$(mktemp -d)"; dd if=/dev/zero of="$extra/more.bin" bs=1024 count=2 2>/dev/null
dir="$(mktemp -d)"; dd if=/dev/zero of="$dir/a.bin" bs=1024 count=1 2>/dev/null
printf '{"extraPaths": "%s"}' "$extra" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "extra path run failed: $(cat "$dir/output.json")"
[ "$(output "$dir" bytes)" = "3072" ] || fail "extra path not counted: $(cat "$dir/output.json")"

# a budget trips it
dir="$(mktemp -d)"; dd if=/dev/zero of="$dir/big.bin" bs=1024 count=2048 2>/dev/null
printf '{"maxMb": "1"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "2 MB over a 1 MB budget must fail"
grep -q "the budget is 1.0 MB" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

echo "smoke ok: workspace size, extra paths, budget gate"

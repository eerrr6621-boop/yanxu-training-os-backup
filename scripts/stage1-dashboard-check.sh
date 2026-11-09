#!/usr/bin/env bash
# Usage: JAVA=/path/to/java NODE=/path/to/node bash scripts/stage1-dashboard-check.sh EMPTY_TEMP_DIR
# Requires Java 17+ and Node 18+. Reuses the existing synthetic API fixture and DOM checks.
# All generated classes, H2 data and evidence stay in the caller's empty directory outside the repo.
# No Main listener, mail delivery, production data, existing check.sh changes or automatic cleanup.
set -euo pipefail
if [[ $# -ne 1 ]]; then
  echo "Usage: JAVA=java NODE=node bash scripts/stage1-dashboard-check.sh EMPTY_TEMP_DIR" >&2
  exit 2
fi
stage1_dashboard_here="$(cd "$(dirname "$0")" && pwd -P)"
stage1_dashboard_repo="$(cd "$stage1_dashboard_here/.." && pwd -P)"
if [[ ! -d "$1" ]]; then
  echo "Create an empty temporary output directory first (for example, with mktemp -d)." >&2
  exit 2
fi
stage1_dashboard_out="$(cd "$1" && pwd -P)"
case "$stage1_dashboard_out/" in
  "$stage1_dashboard_repo/"*) echo "Output must be outside the repository." >&2; exit 2 ;;
esac
shopt -s nullglob dotglob
stage1_dashboard_entries=("$stage1_dashboard_out"/*)
if (( ${#stage1_dashboard_entries[@]} )); then
  echo "The temporary output directory must be empty; no existing data will be reused." >&2
  exit 2
fi
stage1_dashboard_java="${JAVA:-java}"
stage1_dashboard_node="${NODE:-node}"
command -v "$stage1_dashboard_java" >/dev/null
command -v "$stage1_dashboard_node" >/dev/null
"$stage1_dashboard_node" -e 'if(Number(process.versions.node.split(".")[0])<18)throw Error("Node 18+ required")'
stage1_dashboard_cp="$stage1_dashboard_repo/lib/h2.jar:$stage1_dashboard_repo/lib/pdfbox-app-3.0.8.jar:$stage1_dashboard_repo/lib/ip2region-3.3.7.jar"
mkdir "$stage1_dashboard_out/classes" "$stage1_dashboard_out/data" "$stage1_dashboard_out/tmp"
# Keep any relative optional lookup and JVM temporary files inside this isolated output directory.
cd "$stage1_dashboard_out"
export YANXU_CITY_PLANNING_COLLECTION_FILE=""
"$stage1_dashboard_java" -Djava.io.tmpdir="$stage1_dashboard_out/tmp" -jar "$stage1_dashboard_repo/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$stage1_dashboard_cp" -d "$stage1_dashboard_out/classes" "$stage1_dashboard_repo"/src/com/training/*.java "$stage1_dashboard_here/M06SettlementBridgeTest.java" "$stage1_dashboard_here/DashboardSettlementReadFixture.java"
"$stage1_dashboard_java" -Djava.io.tmpdir="$stage1_dashboard_out/tmp" -cp "$stage1_dashboard_out/classes:$stage1_dashboard_cp" com.training.DashboardSettlementReadFixture "$stage1_dashboard_out/data" "$stage1_dashboard_out/project-api-evidence.json"
"$stage1_dashboard_node" --check "$stage1_dashboard_repo/web/app.js"
"$stage1_dashboard_node" "$stage1_dashboard_here/stage1-dashboard-dom.test.cjs" "$stage1_dashboard_repo" "$stage1_dashboard_out"
printf 'Stage 1 dashboard checks passed. Synthetic output retained at: %s\n' "$stage1_dashboard_out"

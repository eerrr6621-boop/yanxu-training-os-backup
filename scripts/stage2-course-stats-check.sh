#!/usr/bin/env bash
set -euo pipefail
stats_root="$(cd "$(dirname "$0")/.." && pwd)"
stats_evidence="${COURSE_STATS_EVIDENCE:-/Users/tongyuqing/Documents/Codex/2026-10-26/yanxu-stage2/evidence/statistics}"
stats_java="${COURSE_STATS_JAVA:-/Users/tongyuqing/.local/opt/zulu17-jre/bin/java}"
stats_run="$(mktemp -d "$stats_evidence/synthetic-run.XXXXXX")"
mkdir -p "$stats_run/classes" "$stats_run/data"
stats_cp="$stats_root/lib/h2.jar:$stats_root/lib/pdfbox-app-3.0.8.jar:$stats_root/lib/ip2region-3.3.7.jar"
"$stats_java" -jar "$stats_root/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$stats_cp" -d "$stats_run/classes" "$stats_root"/src/com/training/*.java "$stats_root/scripts/stage2-course-stats-test.java"
"$stats_java" -Xmx512m -cp "$stats_run/classes:$stats_cp" com.training.Stage2CourseStatsTest "$stats_run/data" | tee "$stats_run/result.txt"
printf '%s\n' "$stats_run" > "$stats_evidence/latest-run.txt"

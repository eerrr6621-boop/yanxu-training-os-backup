#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
course_evidence="${1:?Pass a new private evidence directory}"
course_java="${STAGE2_COURSE_JAVA:-/Users/tongyuqing/.local/opt/zulu17-jre/bin/java}"
course_node="${STAGE2_COURSE_NODE:-/Users/tongyuqing/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node}"
if [[ -e "$course_evidence" ]]; then echo 'Evidence run directory must not already exist' >&2; exit 2; fi
mkdir -p "$course_evidence/classes" "$course_evidence/read-data" "$course_evidence/maintenance-data"
export YANXU_CITY_PLANNING_COLLECTION_FILE=""
"$course_node" scripts/stage2-course-ui.test.cjs > "$course_evidence/ui.log" 2>&1
"$course_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp 'lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar' -d "$course_evidence/classes" src/com/training/*.java scripts/M04CourseMaintenanceTest.java scripts/stage2-course-read.java > "$course_evidence/compile.log" 2>&1
"$course_java" -cp "$course_evidence/classes:lib/h2.jar" com.training.M04CourseMaintenanceTest "$course_evidence/maintenance-data" > "$course_evidence/maintenance.log" 2>&1
"$course_java" -cp "$course_evidence/classes:lib/h2.jar" com.training.Stage2CourseReadTest "$course_evidence/read-data" > "$course_evidence/read.log" 2>&1
cat "$course_evidence/ui.log" "$course_evidence/maintenance.log" "$course_evidence/read.log"

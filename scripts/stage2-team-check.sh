#!/usr/bin/env bash
# Compile/test only in a caller-created empty directory outside the repository. No server or cleanup.
set -euo pipefail
[[ $# -eq 1 && -d "$1" ]] || { echo 'Usage: JAVA=java NODE=node bash scripts/stage2-team-check.sh EMPTY_OUTPUT_DIRECTORY' >&2; exit 2; }
team_repo="$(cd "$(dirname "$0")/.." && pwd -P)"
team_out="$(cd "$1" && pwd -P)"
case "$team_out/" in "$team_repo/"*) echo 'Output must be outside repository' >&2; exit 2;; esac
shopt -s nullglob dotglob
team_entries=("$team_out"/*)
(( ${#team_entries[@]} == 0 )) || { echo 'Output must be empty; existing data never reused' >&2; exit 2; }
team_java="${JAVA:-java}"
team_node="${NODE:-node}"
team_cp="$team_repo/lib/h2.jar:$team_repo/lib/pdfbox-app-3.0.8.jar:$team_repo/lib/ip2region-3.3.7.jar"
mkdir "$team_out/classes" "$team_out/data" "$team_out/regression-data" "$team_out/tmp"
cd "$team_out"
export YANXU_CITY_PLANNING_COLLECTION_FILE=''
export YANXU_LOGIN_MAIL_TRANSPORT=disabled
"$team_java" -Djava.io.tmpdir="$team_out/tmp" -jar "$team_repo/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$team_cp" -d "$team_out/classes" "$team_repo"/src/com/training/*.java "$team_repo/scripts/IntegrationWorkflowTest.java" "$team_repo/scripts/stage2-team-todo-test.java"
"$team_java" -Djava.io.tmpdir="$team_out/tmp" -cp "$team_out/classes:$team_cp" com.training.Stage2TeamTodoTest "$team_out/data"
"$team_java" -Djava.io.tmpdir="$team_out/tmp" -cp "$team_out/classes:$team_cp" com.training.IntegrationWorkflowTest "$team_out/regression-data"
"$team_node" "$team_repo/scripts/stage2-team-ui.test.cjs"
printf 'Team todo evidence retained: %s\nNo listener, ports, external API, mail or production input used.\n' "$team_out"

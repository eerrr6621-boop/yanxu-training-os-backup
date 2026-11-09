#!/usr/bin/env bash
set -euo pipefail
umask 077
export YANXU_LOGIN_MAIL_TRANSPORT=disabled JAVA_TOOL_OPTIONS= JDK_JAVA_OPTIONS= _JAVA_OPTIONS= YANXU_CITY_PLANNING_COLLECTION_FILE=""
for key in ${!YANXU_LOGIN_SMTP_@}; do unset "$key"; done
scripts="$(cd "$(dirname "$0")" && pwd)"
app="${APP_ROOT:-$(cd "$scripts/.." && pwd)}"
java="${SHARED_MAILBOX_JAVA:-/Users/tongyuqing/.local/opt/zulu17-jre/bin/java}"
products="${SHARED_MAILBOX_PRODUCT_CLASSES:-}"
command -v "$java" >/dev/null
[[ -f "$app/lib/ecj.jar" && -f "$app/lib/h2.jar" ]] || exit 2
if [[ -n "$products" ]]; then
  [[ -f "$products/com/training/FirstBindStore.class" && -f "$products/com/training/Auth.class" ]] || exit 2
  products="$(cd "$products" && pwd)"
fi
# Reuse FirstBindTest's strict owned-directory admission, without invoking its test main.
tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-first-bind-r26.XXXXXX")"
tmp="$(cd "$tmp" && pwd -P)"
trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/classes" "$tmp/data" "$tmp/rejected"
: > "$tmp/.first-bind-test-root"
libs="$app/lib/h2.jar:$app/lib/pdfbox-app-3.0.8.jar:$app/lib/ip2region-3.3.7.jar"
if [[ -z "$products" ]]; then
  products="$tmp/product-classes"; mkdir "$products"
  "$java" -jar "$app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$libs" -d "$products" "$app"/src/com/training/*.java
fi
"$java" -jar "$app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$products:$libs" -d "$tmp/classes" "$scripts/FirstBindTest.java" "$scripts/SharedMailboxTest.java"
cp="$tmp/classes:$products:$libs"
cd "$tmp"
if (cd "$tmp/rejected" && "$java" -cp "$cp" com.training.SharedMailboxTest "$tmp/data") > "$tmp/rejected.log" 2>&1; then
  echo 'Missing explicit data.dir must reject before opening any database.' >&2; exit 1
fi
[[ ! -e "$tmp/rejected/data" && ! -e "$tmp/data/training.mv.db" ]] || exit 1
"$java" -Ddata.dir="$tmp/data" -cp "$cp" com.training.SharedMailboxTest "$tmp/data"
"$java" -Ddata.dir="$tmp/data" -cp "$cp" com.training.SharedMailboxTest --reopen "$tmp/data"

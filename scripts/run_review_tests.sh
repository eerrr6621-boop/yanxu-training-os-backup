#!/usr/bin/env bash
# 研序优化分支测试一键运行脚本（只用全新合成数据）。
#
# 用法：  bash scripts/run_review_tests.sh <依赖目录>
#   <依赖目录>：现有系统的 lib 目录，内含 h2.jar、pdfbox-app-*.jar、ip2region-*.jar（只读取，不修改）。
#
# 安全约定（脚本本身保证）：
#   - 所有数据库都在 /tmp 下新建的临时目录里创建，测试数据全部是编造的合成数据；
#   - 不读取、不写入任何正式数据目录；不停止、不重启、不修改正在运行的系统；
#   - 需要启动服务的测试只绑定 127.0.0.1，并使用系统随机分配的空闲端口；
#   - 每个 Java 进程最多使用 512MB 内存，以低优先级（nice 10）运行；
#   - 代码先复制到临时目录再编译运行，不改动当前目录。
# 结束时打印汇总；完整日志在打印出的临时目录里，请原样发回。
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)
LIB=${1:-}
if [ -z "$LIB" ] || [ ! -d "$LIB" ]; then echo "用法：bash scripts/run_review_tests.sh <依赖目录（含 h2.jar 等）>"; exit 2; fi
LIB=$(cd "$LIB" && pwd)
JAVA=${JAVA:-java}; JAVAC=${JAVAC:-javac}
VERSION=$("$JAVA" -version 2>&1 | grep -m1 -i version)
echo "Java：$VERSION"
case "$VERSION" in *\"1[7-9].*|*\"2[0-9].*) ;; *) echo "需要 Java 17 或更高版本"; exit 2;; esac
H2=$(ls "$LIB"/h2*.jar 2>/dev/null | head -1); PDF=$(ls "$LIB"/pdfbox-app*.jar 2>/dev/null | head -1); IP=$(ls "$LIB"/ip2region*.jar 2>/dev/null | head -1)
for jar in "$H2" "$PDF" "$IP"; do [ -n "$jar" ] && [ -f "$jar" ] || { echo "依赖目录缺少 h2 / pdfbox-app / ip2region 的 jar：$LIB"; exit 2; }; done
FREE=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)
echo "当前可用内存：${FREE}MB"
if [ "${FREE:-0}" -lt 1024 ] && [ "${FORCE:-}" != "1" ]; then echo "可用内存不足 1GB，按约定暂不运行。"; exit 3; fi

WORK=$(mktemp -d /tmp/yanxu-review-XXXXXX)
echo "临时目录：$WORK"
SERVER=""
cleanup() { if [ -n "$SERVER" ]; then kill "$SERVER" 2>/dev/null; wait "$SERVER" 2>/dev/null; fi; }
trap cleanup EXIT
CODE="$WORK/code"; cp -r "$HERE" "$CODE"; rm -rf "$CODE/lib"; ln -s "$LIB" "$CODE/lib"
OUT="$WORK/classes"; mkdir -p "$OUT"
CP_LIB="$H2:$PDF:$IP"; CP="$OUT:$CP_LIB"
JOPTS=(-Xmx512m -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -Dsemantic.port= -Dsemantic.token= -Drecommendation.cpa.enabled=false)
export YANXU_SEMANTIC_PORT= YANXU_SEMANTIC_TOKEN=

echo "=== 编译（正式依赖，不用编译桩）"
# 只编译要运行的测试及其夹具（审查副本里个别无关旧脚本引用了未随包提供的类）。
TESTS=(DispatchConfirmationArchiveTest DispatchOccupationWindowTest RecommendationV2BookingWindowTest LegacyLoginThrottleTest PureRulePolicyTest TravelReferenceTest
  RecommendationV2RankingTest RecommendationV2JointPlannerTest RecommendationScaleHostTest RecommendationV2HostTest RecommendationV2Fixture RecommendationConfirmedExperienceHostTest
  M04RecommendationDecisionHostTest M04RecommendationQualificationTest IntegrationLoginVerificationHostTest ReleaseMigrationCheck DbMigrationCheck IntegrationRecommendationHttpFixture)
SOURCES=(); for t in "${TESTS[@]}"; do SOURCES+=("$CODE/scripts/$t.java"); done
if ! nice -n 10 "$JAVAC" -nowarn -encoding UTF-8 -proc:none -cp "$CP_LIB" -d "$OUT" "$CODE"/src/com/training/*.java "${SOURCES[@]}" > "$WORK/compile.log" 2>&1; then
  echo "编译失败，见 $WORK/compile.log"; tail -20 "$WORK/compile.log"; exit 1
fi
echo "编译完成：$(find "$OUT" -name '*.class' | wc -l) 个类"

PASS=0; FAIL=0; SUMMARY=()
run() { local name=$1; shift; echo "=== $name"
  if (cd "$CODE" && nice -n 10 "$@") > "$WORK/$name.log" 2>&1; then PASS=$((PASS+1)); SUMMARY+=("通过  $name：$(grep -v '^Picked up' "$WORK/$name.log" | tail -1)")
  else FAIL=$((FAIL+1)); SUMMARY+=("失败  $name：见 $WORK/$name.log"); fi
  grep -v '^Picked up' "$WORK/$name.log" | tail -3; }
fresh() { local d="$WORK/yanxu-recommendation-v2-$1"; mkdir -p "$d"; echo "$d"; }

echo "##### 一、纯逻辑测试（不需要数据库）"
for t in DispatchConfirmationArchiveTest DispatchOccupationWindowTest RecommendationV2BookingWindowTest LegacyLoginThrottleTest PureRulePolicyTest TravelReferenceTest RecommendationV2RankingTest RecommendationV2JointPlannerTest; do
  run "$t" "$JAVA" "${JOPTS[@]}" -cp "$CP" "com.training.$t"
done
if command -v node >/dev/null; then
  run RecommendationV2UI node "$CODE/scripts/RecommendationV2UI.test.cjs"
  run RecommendationCpaUI node "$CODE/scripts/RecommendationCpaUI.test.cjs"
else SUMMARY+=("未运行  界面测试：服务器没有 node"); fi

echo "##### 二、数据库测试（全新合成 H2）"
run RecommendationScaleHostTest "$JAVA" "${JOPTS[@]}" -cp "$CP" com.training.RecommendationScaleHostTest "$(fresh scale)"
run RecommendationV2HostTest "$JAVA" "${JOPTS[@]}" -cp "$CP" com.training.RecommendationV2HostTest "$(fresh host)"
run RecommendationConfirmedExperienceHostTest "$JAVA" "${JOPTS[@]}" -cp "$CP" com.training.RecommendationConfirmedExperienceHostTest "$(fresh experience)"
run M04RecommendationDecisionHostTest "$JAVA" "${JOPTS[@]}" -cp "$CP" com.training.M04RecommendationDecisionHostTest "$(fresh m04)"
run IntegrationLoginVerificationHostTest "$JAVA" "${JOPTS[@]}" -cp "$CP" com.training.IntegrationLoginVerificationHostTest "$(fresh login)"
run ReleaseMigrationCheck "$JAVA" "${JOPTS[@]}" -Djava.io.tmpdir="$WORK" -cp "$CP" com.training.ReleaseMigrationCheck --work-root "$WORK"

echo "##### 三、接口测试（只绑定 127.0.0.1 的临时服务）"
if command -v node >/dev/null; then
  run IntegrationRecommendationHttp node "$CODE/scripts/IntegrationRecommendationHttp.cjs" --java "$JAVA" --classes "$OUT"
  ROOT=$(fresh http); mkdir -p "$ROOT/data"
  PORT=$(node -e "const s=require('net').createServer();s.listen(0,'127.0.0.1',()=>{console.log(s.address().port);s.close()})")
  PASSWD=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' | head -c 32)
  printf '{"host":"127.0.0.1","port":%s,"password":"%s"}' "$PORT" "$PASSWD" > "$ROOT/PRIVATE.json"
  (cd "$CODE" && exec nice -n 10 "$JAVA" "${JOPTS[@]}" -Dbind.address=127.0.0.1 -Dbootstrap.demo=false -Dbootstrap.admin.password="$PASSWD" \
     -Dintegration.v2.root="$ROOT" -Ddata.dir="$ROOT/data" -cp "$CP" com.training.RecommendationV2Fixture "$PORT") > "$WORK/v2-http-server.log" 2>&1 &
  SERVER=$!
  for _ in $(seq 1 120); do
    kill -0 "$SERVER" 2>/dev/null || break
    if [ -f "$ROOT/METADATA.json" ] && node -e "require('net').connect($PORT,'127.0.0.1').on('connect',()=>process.exit(0)).on('error',()=>process.exit(1))"; then break; fi
    sleep 1
  done
  run RecommendationV2Http node "$CODE/scripts/RecommendationV2Http.cjs" "$ROOT"
  kill "$SERVER" 2>/dev/null; wait "$SERVER" 2>/dev/null; SERVER=""
else SUMMARY+=("未运行  接口测试：服务器没有 node"); fi

echo
echo "##### 汇总：通过 $PASS 项，失败 $FAIL 项"
for line in "${SUMMARY[@]}"; do echo "$line"; done
echo "完整日志目录：$WORK （请把整个目录打包发回；核对后可删除）"
[ "$FAIL" -eq 0 ]

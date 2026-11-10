# 2026-11-10 测试入口与历史合同清单

本轮基线为 `a77e9b6f919df58f09ef6a4b55457b22624a392f`，仅恢复测试资源、修改测试/夹具/入口及本说明，不改产品，不启用 AI，不部署。**当前测试通过不等于旧推荐、模型语义或旧外部问卷合同通过。**

## 来源与恢复决定

- 历史原件来自 `eerrr6621-boop/yanxu-training-os` 的公开源版本 `5051764d3062843aba7597e6850b4784be50b5fe`，逐文件 Git blob / SHA-256 / 字节数记在 `scripts/legacy/restored-provenance.json`。不是重新编造测试或从压缩 WebP 反造 PNG。
- GitHub 连接器另行核验了 `eerrr6621-boop/yanxu-training-os-backup` 的 `main`：真实位置为 `app/test.js`、`app/test_integrity.js`、`app/test_faculty.js`、`app/design/login/v13r10-originals/silver-light-background.png`，对应 blob 分别为 `c9ce01857d2a4aeebc237792c0b50a716ccb570e`、`82d8fac79e3fa4130f703b416b02a3417b3d45f2`、`1af2a2bb5f382ccb2fd11b0bf9d212164e98f4f4`、`ff9c324355f052ddadfb9c6d2dbd098105a8c960`，与上述固定源版本完全一致。当前发布树根目录已相当于旧 `app/`，因此恢复到根目录，而非再套一层 `app/`。
- 恢复全部 24 个已跟踪的 `design/` 设计原件。银光背景 PNG SHA-256 为 `cd964012831a92a549d07fd986ca599e58427ac2831f5fa4d49906f1172f0873`，1586×992，与现行测试固定摘要相符。原 WebP 和产品 CSS 不变。`test_login_background.cjs` 原 28 项通过。
- 三个根 JS 原文件先完整恢复并实际运行，均被当前夹具要求显式重新登录的检查阻断。迁移前的**完整原文**分别保存在 `scripts/legacy/test*.js.original`；当前三个根入口仅调整现行夹具或明确退役合同，仍保留其他 CRUD、权限、上传、解析、财务、流程、地区与安全断言。

## 原入口逐项处置

完整原 `check.sh` 行号与变更理由：`scripts/legacy/check-entry-changes.json`。它列出 26 次 Python 调用（24 个不同文件，两个曾重复调用）和两项编译/HTTP入口变化。原断言标签、原行号、当前是否保留原标签：`scripts/legacy/assertion-label-ledger.json`。归档不计通过。

### Stage2 编译与合成数据

- 原 `check.sh:167` 先编译 `stage2-team-todo-test.java`，它调用的 `IntegrationWorkflowTest` 到原第179行才编译。第167行同批加入这个真实测试依赖，不加桩、不改 Stage2 断言。原独立 `stage2-team-check.sh` 本来已正确列依赖，本体 52 项无需修业务。
- 完整独立入口随后暴露 `IntegrationWorkflowTest` 缺 `q_sends` 合成表。现行 `LegacyOwnershipIntegration.scope` 从数据库解析子记录，不能再只有传入 Map。补最小 `id,questionnaire_id` schema 与对应合成行，使原“跨机构看不到问卷子记录”断言检查真实持久化子记录，不跳过授权。
- 修后独立入口：Stage2TeamTodo 52、IntegrationWorkflow 53、Stage2TeamUI 26 项全部通过。

### 24 个 Python 文件：正式历史离线分组，不是删除

逐文件导入、理由、原摘要及分类见 `scripts/legacy/semantic-inventory.json`。

- 19 个是保留 Python worker 的纯确定性解析、mock Encoder 或与 Java 的协议一致性测试。它们**不等于当前生产在运行 Python**。`Main.start` 首先执行 `PureRulePolicy.requireNoModelChannels`，拒绝 semantic port/token 与 CPA 开关；旧推荐路由又在调用旧推荐/worker之前返回410。当前Java服务没有通向这些 Python 测试入口的调用。
- 5 个是历史后训练数据或评估元数据合同：`test_posttrain_v3_data`、`test_posttrain_v3_pipeline_validation`、`test_posttrain_v4_data`、`test_posttrain_v4_metrics`、`test_posttrain_v4_audit`。当前发布树没有对应训练/评估辅助模块；不把这些旧实验源码混入当前产品以求默认全绿。
- 24 个原测试文件逐字节恢复，无断言删除、无新增 skip。默认 `check.sh` 明示它们 NOT RUN，不把它们计入当前通过数量。所有原有 Java 确定性解析/协议回归仍在默认入口，例如 RequirementInput/Resolution、Evidence*、LearnerArtifact、MethodContrast、PostposedExclusion；没有以名称含 semantic 为由删除这些检查。
- 新 `scripts/check_legacy_semantic.py` 是独立显式历史入口。必须给实际编译Java类和全新外部输出目录，编译真实协议测试依赖；所有 skip 都判失败。网络及模型包导入被拒；运行超时/启动错误记 FAILED并继续记录后续套件，而不把缺失算通过。
- 不给 `--historical-source` 时，19项执行，另5项明确 NOT_RUN，最终退出2。给固定历史源码后，先核验其 Python 辅助源和原测试摘要，才运行5项元数据检查。绝不下载模型或训练。
- 本轮实际历史离线结果为 **24/24 文件、234 tests、0 skip、0 not-run**，不是24项当前产品/模型效果通过。

```sh
# CLASSES 是编译好的当前产品类，OUT 是新建空目录，均在源码树外。
python3 -B scripts/check_legacy_semantic.py --java "$JAVA17" --classes "$CLASSES" --output "$OUT" \
  --historical-source "$PINNED_5051764_SOURCE"
```

### 原需求输入55项与现行禁用合同不是等价覆盖

原 `test_requirement_input_resolution_http.cjs` 全文保存在 `scripts/legacy/test_requirement_input_resolution_http.cjs.original`，更早 `test_requirement_input_http.cjs` 原样保留。原55项依赖旧 `/api/teacher-recommendations` 返回候选、需求台账和旧评分结构，现行产品在进入解析前返回410。不得改产品恢复旧路由，也不得把每个原“成功”简单改成“410”后仍称55项通过。

当前同名入口专门验证**现行退役合同**：5个旧路由的GET/POST均 HTTP410+JSON410、no-store、指向V2、无伪成功数据；匿名401；AI advice410且说明纯规则；现行V2仍按结构化输入验证而非一律410；退役请求不改业务集合。共51项。另把现有 `PureRulePolicyTest` 加入默认入口，13项验证配置禁用且在数据库/监听前拒绝启动。

| 原55中的功能类别 | 当前保留或对应检查 | 边界，不夸大覆盖 |
| --- | --- | --- |
| 引导文本/8字段台账/原文来源/未知字段与类型 | `RequirementInputTest.java`、`RequirementInputResolutionTest.java`、`RequirementContractTest.java`、`test_requirement_input_ui.cjs` | 保留解析器的直接/界面参考断言；并不代表旧HTTP合同仍可用 |
| 未来受众与硬适配、未知目标/偏好保持待核 | `RequirementAudienceScopeTest.java`、`AudienceContractTest.java`、`FutureActivityAudienceTest.java` 等默认Java回归 | 自由文本旧评分/放行本身退役，无“V2已等价覆盖”的主张 |
| 当前结构化资格、缺证不放行、显式课程关联 | `RecommendationV2HostTest.java`、`RecommendationV2BoundaryTest.java`、`M04RecommendationQualificationTest.java`、`RecommendationConfirmedExperienceHostTest.java` | V2依赖结构化课程资格/显式人工核对，不把旧任意文本理解当现行功能 |
| 关联来源、跨机构可见范围、重登和撤权 | `IntegrationWorkflowHttp.cjs`、`RecommendationV2HostTest.java`、`RecommendationV2Http.cjs` | 本轮workflow65项通过；V2独立套件须在整体验证中另记真实结果 |
| 修改/清空日期与课时不偷偷恢复旧值 | 原55归档；`RequirementInputResolutionTest.java`保留直接合同；V2边界/booking测试检查其明确时段模型 | 没有把自由文本计划字段与V2明确时段宣称同一种接口合同 |
| 推荐不写回需求/不自动派课、输出不泄露 | 新51退役检查、当前根integrity/faculty、`RecommendationV2HostTest.java`/`RecommendationV2Http.cjs` | 退役请求无副作用已验证；V2正向业务结果另算，不能以51代替 |

### 三个根 JS 与 Workflow HTTP

- 三根入口均按真实 `IntegrationHttpFixtures` 合同显式刷新配置发布后的会话。保留该夹具的旧token401断言。正向确认新增真实preview/version/request_id；根api/integrity额外检查缺少版本时409。必要的合成排课起止时间写在初始夹具，不伪造确认事实。
- `test_integrity.js` 正向项目编辑使用审核后的 `projectEditFields` 白名单，所有明确越权负例不删。旧推荐正向语义段原行837–865归档，当前检查410、无结果、指向V2；旧匿名/只读拒绝与不写排课仍在。
- `test_faculty.js` 只把依赖旧推荐成功/旧评分语义的段落分离。精确原行范围见 `faculty-retired-ranges.json`。师资等级CRUD、PPTX/PDF解析、原文声明、人工校准/替换/重解析、并发旧简历防误改、地区维护、草稿字段、权限等原断言仍跑。新的410/no-mutation不能叫旧推荐语义通过。
- `IntegrationWorkflowHttp.cjs` 保留前半真实流程、授权、私有集合/统计、显式确认与交付。旧推荐和异步job段完整归档；当前测试退役响应不泄露、不写业务，保留配置变更撤销全部角色旧会话并检查撤权后当前需求读403。原项目helper变更保持，未覆盖。
- 新发现的旧外部问卷合同：`LegacyOwnershipIntegration.rejectPublicQuestionnaire` 明确拒绝 `/q/send`、`/q/pub`、`/q/answer`。根api原121–135行完整保留在归档；当前检查409、无公开链接、无伪造答卷，同时仍跑内部创建/发布/统计。现行调查导入/复核统计由现有 M07/M08 对应套件负责，非恢复旧外发。
- integrity末尾原1MiB登录fetch在当前4096字节登录上限早拒后发生ECONNRESET/EPIPE。改用原有完整响应校验器，分片发送同一超大JSON，收到服务器响应即停止写入。要求实际已发字节>4096且完整HTTP413+JSON413；网络错误/超时仍失败。不是捕获reset当通过，也不调高服务端上限。
- 当前实跑：根api63/63、integrity149/149、faculty52/52；workflow65/65。这些是迁移后的现行检查数，不能与历史断言直接相加冒称未变。

如需显式复现归档原合同（当前退役路由会真实失败），使用 `scripts/run_legacy_http.cjs --acknowledge-historical-contract`，可选 `api / integrity / faculty / requirement / workflow`，并传原入口所需的隔离地址或Java/classes。它只执行保存的原断言，不复活任何产品入口；禁用模型开关。此次没有运行历史模型或恢复历史产品服务。

## 验证边界

所有本轮运行仅全新合成H2、显式127.0.0.1临时端口、Java17/ECJ、512MiB上限、邮件关闭。设计原件恢复不涉及产品逻辑或私有业务数据。分项通过不是完整 `check.sh` 已通过；总入口需在各子任务补丁合并后重新完整运行，任何后续失败必须继续单列，不能隐藏。

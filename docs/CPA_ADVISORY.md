# 点击式本机 CPA 辅助解释

V2 先执行确定性规则核对；每项需求的“AI 辅助比较本次短名单”按钮才请求 `/api/teacher-recommendations/decision/v2/advice`。启动、页面打开、普通或联合分析、读取更多候选、人工核对的读取与保存均不调用 CPA。AI 只从操作人本次明确记录的事实中选择线索，不参与资格、档期、出行梯度、排序、联合选择或派课。

产品默认关闭。仅在研序后端实际 JVM 启动参数加入 `-Drecommendation.cpa.enabled=true` 可启用；移除或设 `false` 可关闭。`run.sh` 不转发任意 `-D` 参数，不能把 `./run.sh -Drecommendation.cpa.enabled=true` 当作启用方式。本轮没有启用真实 CPA；测试只使用隔离数据与内存客户端或浏览器模拟回执。

通道只访问 `http://127.0.0.1:8317/v1/chat/completions`，不接受浏览器提供供应商、地址或密钥，不读取 CPA 认证文件或管理 API，不配置客户端 key，不使用代理或重定向。401/403 表示鉴权阻塞，保留规则结果，由用户通过已有鉴权流程处理。浏览器只访问研序自己的 API。

请求模型为 `gemini-3.8-flash-high`，发送 `reasoning_effort: high`。记录请求和响应 model/modelVersion；名称不同本身不判定降级，High 实际执行仍未独立证实。没有上游 token 或费用硬上限，本地停止、超时或断连不保证上游停止或不计费。

## 先由操作人确认，停止自然履历推断

当前出站版本不从自然语言履历、课程标题、历史记录或需求原文自动推断角色、对象、深度、否定或限制。旧自然语言投影不能产生 `STAFF_CONFIRMED` 事实。未确认材料只在获权本地页面作为原文展示，不进入模型请求。

入口保持原页面：智能推荐 → 核对当前候选 → 候选卡的“核对本次服务与档期” → 原“本次人工核对”表单内的可选“本次人工确认课程经历”。原服务、完整档期、出行事实、核实时间和有效期的字段语义保持不变。一次保存提交完整表单，不另设经历保存接口，也不会因经历确认而自动续期旧交通事实。

经历确认默认不启用。新记录未确认时沿用旧保存格式，不含 `experience_review`。已有确认时必须明确选择保留、重新确认或清除，不能默默省略：

| 操作 | 行为 |
| --- | --- |
| 保留当前确认 | 仅当前有效的记录可选；须重新勾选确认已核对完整需求、全部来源及本表事实，发送原已存结构化对象 |
| 重新确认 | 所有新经历和课程需求字段均为空，不能根据原文或已有确认自动预填；用户逐项填写并勾选确认 |
| 明确清除 | 须单独勾选确认，完整保存请求中发送 `experience_review: null`；不删除历史审计 |

编辑需求或限制后，需求已核对勾选失效；编辑经历行后，该行确认失效；继续编辑或增删行会清除最终确认。保存前必须重新明确确认。保存成功清除旧分析，重新核对候选后仍须另行点击 AI 按钮才可能调用模型。

本地来源最多 8 份，每份原文不超过 1600 字符，由后端按当前权限和候选归属完整返回。页面用 `textContent` 展示，标为“未核原文，不出站”；不删句、不截断、不依据主题删去否定或限制。来源超过容量返回 `UNAVAILABLE`，不能确认经历，仍可保存原服务与交通事实；已有经历确认需明确清除或待来源可完整核对后重新确认。

## 人工核对记录契约

原 `/assessments/read` 回执保留旧字段并增加：

- `experience_review`：已存对象或 `null`。
- `experience_sources`：完整获权 `Evidence.value`，包括本地证据 ID、候选 ID、来源版本及原文；只供本地人工核对。
- `experience_review_status`：`NONE`、`CURRENT`、`STALE` 或 `UNAVAILABLE`；失效或不可用时显示固定说明。

原 `/assessments/save` 请求仍包含 `input`、`teacher_id`、`expected_revision`、同次 `request_id`、完整 `facts` 和本地 `reason`；可选增加 `experience_review`。新确认对象的精确字段为：

| 位置 | 字段 |
| --- | --- |
| 根 | `schema_version: experience_review_v1`、`confirmed: true`、`reviewed_entire_requirement: true`、`reviewed_all_sources: true`、`requirement`、`source_manifest`、`claims` |
| 单课程需求 | `topic`、单值 `audience`、单值 `depth`、`limits` |
| 完整来源清单 | 每项仅 `evidence_id`、`source_version`，必须和当前候选全部完整来源精确一致，不能遗漏或跨候选 |
| 经历声明 | 全记录最多 4 条；每条为 `evidence_id`、`source_version`、`topic`、单值 `role`、单值 `assertion`、单值 `audience`、单值 `depth`、`limits`、`confirmed: true` |

`topic` 必须从固定审核课程词表显式选择，声明课程必须等于已确认需求课程。一次只确认一个课程，多课程需求须分别核对，不能把不同课程的对象或深度压平。用户须确认完整需求及其限制都能由所选字段表达；不能表达时不确认。每行仅描述同一来源中的一个经历事件，入门主讲与进阶学员必须分行。来源可以经过人工核对但没有任何经历声明；空声明不表示没有经历或能力。

| 维度 | 允许枚举 |
| --- | --- |
| 角色 | `TEACHING`、`LEAD_TEACHING`、`ASSISTANT`、`PARTICIPANT`、`EXPERIENCE` |
| 陈述性质 | `AFFIRMED`、`DENIED`、`LIMITED`、`UNKNOWN` |
| 对象 | `NEW_SUPERVISORS`、`BEGINNERS`、`EXPERIENCED_PROJECT_MANAGERS`、`MANAGERS`、`EMPLOYEES`、`PARENTS`、`CHILDREN`、`UNKNOWN` |
| 深度 | `INTRODUCTORY`、`PRACTICAL`、`ADVANCED`、`UNKNOWN` |
| 经历限制 | `ONLY_PARTICIPATED`、`ASSISTANT_ONLY`、`INTRODUCTORY_ONLY`、`LIMITED_LEAD_EXPERIENCE`、`LIMITED_TEACHING_EXPERIENCE`、`ROLE_SCOPE_UNCLEAR`、`NONE_RECORDED`、`UNKNOWN` |
| 需求限制 | `REQUIRE_LEAD_TEACHING`、`REQUIRE_TEACHING_EXPERIENCE`、`REQUIRE_PRACTICAL_EXPERIENCE`、`EXCLUDE_THEORY_ONLY`、`INTRODUCTORY_ONLY`、`ADVANCED_ONLY`、`ONLY_INCLUDED_TOPICS`、`NONE_RECORDED`、`UNKNOWN` |

每个限制列表至少明确选择一项，不能把未操作当作“没有限制”；`NONE_RECORDED` 或 `UNKNOWN` 必须单独使用。角色范围、深度限制和范围重叠的肯定/否定矛盾会拒绝保存。所有来源版本、权限、核对有效期和请求版本均由后端重新核验，旧记录失效后不能继续外发。

## 发给 CPA 的字段

出站 `schema_version` 为 `topic_advisory_staff_confirmed_v3`，投影版本为 `cpa-staff-confirmed-projection-v3`。`/advice` 客户端仍只发送 `analysis_id`、`request_id`，不能提交原文或伪造的已核事实。后端从已保存且当前有效的人工核对事件构造投影。

| 位置 | 唯一允许的字段 |
| --- | --- |
| 请求根 | `schema_version`、`course_requirement`、`candidates` |
| `course_requirement` | 人工明确确认的单值 `topic`、`audience`、`depth` 以及 `limits`；参与比较的已核候选必须具有完全一致的需求 |
| 候选 | 本次临时 `candidate_id`（C1…C6）、`course_tags`、`evidence`；最多 6 位规则短名单候选 |
| 每条来源 | 本次临时 `evidence_id`（E1…En）、`kind`、固定模板 `summary`、`claims` |
| 每条出站事实 | `topic`、`role`、`assertion`、`audience`、`depth`、`limits`、`verification: STAFF_CONFIRMED` |

所有固定摘要由已存枚举生成，最多 1200 字符，不使用源原文或模型自由文字。已核来源没有声明时保留来源及空 `claims`，使用固定“未记录声明，不据此判断能力”摘要；只有含非 `UNKNOWN` 陈述的来源可被模型选择。短名单内尚无人工确认的候选只发送临时候选号和空事实，不能补造经历。

真实候选 ID、证据 ID、源引用、源版本、姓名（包括原文中的第三人姓名）、电话、证件、邮箱、单位、地点、原始名单、整台账、原文、保存理由、事实说明、记录人及权限信息均不进入 CPA 请求。本地来源 ID 只在服务端映射和获权页面保留。

没有当前有效且明确的人工确认时返回 `RULES / NO_CONFIRMED_EXPERIENCE`；来源或有效期变化返回 `RULES / STALE_CONFIRMED_EXPERIENCE`。两者都在调用前结束，本次零发送，不消费模型调用；不据此判断候选能力。不同候选的已核需求不一致、结构或归属不合法时也拒绝发送，不能静默删去冲突事实。

## 输出与页面

模型只可返回 `suggestions`，每位短名单候选一项，仅有 `candidate_id`、所属 `evidence_ids`、一个 `reason_codes`。允许 `TOPIC_RELATED`（至少一条可选择来源）或 `TOPIC_UNCLEAR`（空选择）。编号、归属、可选择性、数量、结构或额外字段不合法均降级 RULES；不接受模型自由理由、角色、资格、排名或动作。

浏览器的 `topicAdvice` 仅含 `reasonCodes`、`evidenceIds`、不超过 500 字符的本地固定 `reason`、`localEvidence`。每条本地来源仅含 `evidenceId`、`candidateId`、`kind`、`title`、`sourceRef`、`sourceVersion`、`summary`、`claims`、`verification: STAFF_CONFIRMED`。每条 claim 使用上述出站事实结构。空 `claims` 或仅 `UNKNOWN` 陈述的来源可展示，但不能成为 AI 选中来源。

页面标题为“本次操作人已确认事实（非资格认证）”。完整展示全部已核来源及固定摘要，标明“AI选中线索”或“同次已核来源（AI未选中）”；`TOPIC_UNCLEAR` 不隐藏未选中的否定与限制。页面只声明事实由操作人记录，不能称为教师本人签认、资质证明或已完成派课。普通读取、缓存读取和 AI 回执均验证结构、大小、枚举和归属；AI 回执除 `model/topicAdvice` 外不得改变规则候选、原证据、硬条件、交通顺序或联合结果。

## 资源、权限和等待

本地并发 1、无排队、重试 0；每分钟最多 6 次且输入累计不超过 64 KiB，每服务进程最多 30 次。payload 16 KiB、封装请求 24 KiB、上游响应 32 KiB，附加本地结构后的辅助结果最多 64 KiB；连接超时 2 秒。`recommendation.cpa.timeoutMillis` 合法值为 1000–45000 整数毫秒，默认 45000；非法配置拒绝启动。进程重启不会保留调用预算，这些限制不等于账号账单或上游 token 硬限。

分析缓存绑定 Auth.Session 身份、10 分钟 TTL 和权限、目录、资料、核对事实、占用、规则与投影版本。发送前和返回前重新核验；在途锁防重复，成功或失败回执按相同有效版本复用，不自动重试。资料或权限变化使旧回执失效。

启用 CPA 且未指定 HTTP 响应限时时，默认 max(30秒, 模型客户端预算向上取整+15秒)，45秒预算对应60秒；显式配置不足时拒绝启动，不覆盖配置。关闭 CPA 时维持原30秒。原 `app.api` fetch 无普通请求固定限时；推荐面板等待回执50秒，明确模型最多45秒、另5秒接收回执余量；浏览器测试观察窗口65秒不放宽模型预算。

“停止等待”或页面50秒截止只中止本页面等待，丢弃迟到成功或错误并保留 RULES；不保证上游停止或不计费。同页该需求暂停再次调用，须重新核对分析；查看更多不解锁，刷新和重新分析也不自动调用模型，不增加轮询。

`CPA_ADVISORY` 日志仅记录不可逆版本、过滤计数、请求/响应模型、用量、耗时、安全请求轨迹及结果状态，不记录完整个人提示、原文、本地事实说明或上游错误正文。COMPLETE 仅表示上游完成且输出结构、归属与本地已核事实对应检查通过，不表示教学能力、课程资格或最终适配已确认。

## 审计格式与代码回退

新读取逻辑支持旧 `RECOMMENDATION-ASSESSMENT-1` 与新 `RECOMMENDATION-ASSESSMENT-2` 事件。省略经历字段且没有已有确认时仍按旧格式保存；显式对象或 `null` 使用新格式。已有确认的新保存如果省略该字段，后端返回409，防止旧客户端隐式清除经历确认。

旧版读取逻辑不能读取新格式事件。本轮业务写入仅发生在隔离测试数据库。交付的恢复包只处理代码增量，不迁移或回退业务事件；如果未来真实业务已写入格式2，应关闭 AI 并保留能读取两种格式的新读取逻辑，不能直接退回不兼容的旧读取逻辑，更不能删除审计事件来消除不兼容。

## 验证边界

合成检查覆盖未确认原文零自动推断与零模型调用、完整来源及版本绑定、明确的人工作者/课程/事件选择、默认未确认、保留/重确认/清除、字段修改后确认失效、同号保存重试、权限撤销、缓存失效、规则和排序不变、50秒停止等待与迟到结果丢弃。七个独立语义反例作为原文对照保留；系统不会再以正则把这些句子变成明确事实。

本轮采用内存客户端、隔离数据库和模拟 AI 页面回执，不使用真实教师资料或真实模型调用证明语义理解。人工输入仍需操作人核实；固定枚举不是任意自然语言需求的自动理解系统。未覆盖真实生产负载、业务数据迁移或全流程发布。High实际执行及历史超时请求的上游状态仍未知。

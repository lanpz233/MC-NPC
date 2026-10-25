# MC-NPC · AI Villager

Paper 1.21.11 上的 AI 伪玩家插件。让一个 FakePlayer（`Villager_A1`）像真实玩家一样**自主生存发展并通关** Minecraft（击败末影龙），不依赖 LLM 也能持续跑。

## 技术栈

| 项目 | 版本 |
|------|------|
| 服务端 | Paper 1.21.11 (build 132) |
| Java  | 21 |
| 映射  | Mojang Mappings |
| 构建  | Gradle + paperweight 2.0.0-beta.24 |

## 架构分层

```
┌─────────────────────────────────────┐
│  ProgressionPlanner   (1s 周期)     │ 科技树 decide()：下一步做什么
├─────────────────────────────────────┤
│  GoalExecutor         (0.5s 周期)   │ tickDig / tickCraft / tickSmelt / tickHunt / tickPortal
├─────────────────────────────────────┤
│  SkillRunner          (事件驱动)     │ walk / mine / strip / descend / ascend / travel /
│                                     │ portal / obsidian / flint / slay / hunt / build /
│                                     │ explore / endportal / dragon
├─────────────────────────────────────┤
│  FakePlayerBody       (物理层)       │ ServerPlayer + FakeConnection，真正的移动/挖方块/放置
├─────────────────────────────────────┤
│  SurvivalController   (10 tick 周期) │ 饥饿、溺水、卡方块、溺水自救、3 秒复活
└─────────────────────────────────────┘

LLM 层（可选，只负责聊天）— LlmRouter + OllamaProvider / OpenAiCompatibleProvider
```

**核心设计**：生存层完全独立运行，每 0.5 秒 tick 一次，不耗 LLM token。LLM 只在玩家主动聊天时介入。

## 目录结构

```
src/main/java/com/example/aivillager/
├── AiVillagerPlugin.java       入口 + 生命周期
├── brain/
│   ├── VillagerBrain.java      大脑聚合层（Planner + Goals + Skills + Survival）
│   └── AutonomyLoop.java       定时调度器
├── npc/
│   ├── FakePlayerBody.java     ServerPlayer + FakeConnection 物理层
│   └── FakeConnection.java     客户端连接伪造
├── progression/
│   └── ProgressionPlanner.java 科技树决策（1s）
├── goals/
│   └── GoalExecutor.java       目标层 tickDig/tickCraft/tickSmelt/...（0.5s）
├── skills/
│   └── SkillRunner.java        技能层
├── survival/
│   └── SurvivalController.java 生存层（10 tick = 0.5s）
├── pathfinding/
│   └── PathFinder.java         A* 寻路
├── llm/                        LLM 聊天模块
├── intent/                     聊天意图解析
├── listener/
├── command/
└── spike/                      压力测试场景
```

## 构建

```bash
./gradlew build
# 输出: build/libs/AI-Villager-0.1.0-SNAPSHOT.jar
```

Paper 1.21.11 运行时就是 Mojang 映射，构建时已通过 `paperweight.reobfArtifactConfiguration = MOJANG_PRODUCTION` 直接产出 Mojang 名字的插件，**不需要 reobf**。

## 部署

1. 下载 [Paper 1.21.11](https://papermc.io/downloads) 到 `server/` 目录
2. 首次启动生成 eula 等配置，`eula=true` 后重启
3. 把构建产物复制到 plugins：

```bash
cp build/libs/AI-Villager-0.1.0-SNAPSHOT.jar server/plugins/AIVillager.jar
```

4. 启动：

```bash
cd server
java -Xms2G -Xmx4G -jar paper-1.21.11-132.jar --nogui
```

## 游戏内命令

```
/aivspike spawn       生成伪玩家 Villager_A1
/aivspike despawn     卸载
/aivspike status      查看状态（位置/HP/饥饿/技能/背包）
/aivspike walk        朝指定点走
/aivspike mine        挖方块
/aivspike act         开始自主通关（主线）
/aivspike stop        停止自主行为
/aivspike say <msg>   让 NPC 说话
/aivspike brain       查看大脑状态
/aivspike llm         LLM 聊天测试
/aivspike stress      压力测试场景
```

NPC 出生点固定 (2.5, 86, 2.5)，背包/装备通过世界 PDC 持久化（键名 `npc_inv_villager_a1`）。

## 日志过滤

所有运行日志走 `[AIV]` 前缀，子模块 tag：

```bash
# PowerShell
Select-String -Path "server\logs\latest.log" -Pattern "\[AIV\]\[(PLAN|GOAL|SKILL|INV|SURVIVAL)\]"
```

| Tag | 含义 |
|-----|------|
| `[PLAN]` | 科技树决策（下一个目标是什么） |
| `[GOAL]` | 目标层执行细节（合成配方选了哪个、挖到了什么） |
| `[SKILL]` | 技能层事件（descend 完成、strip 挖到钻石） |
| `[INV]`  | 背包变化（trimOverflow 丢了什么、收集了什么） |
| `[SURVIVAL]` | 生存层事件（复活、卡方块顶出、流体跳出） |

## 已知设计约束

- **工具耐久留最后 1 点不用**：`mineWithBestTool` / `countUsableItem` / `trimOverflow` 里都有这条规则，避免"把唯一工具炸没"
- **死亡 3 秒自动复活**：`SurvivalController.REVIVE_DELAY_TICKS = 4`（每 tick 10 游戏 tick ≈ 2-3 秒）
- **流体自救**：脚部格是水/岩浆时，生存层自动传送出流体（优先上方，不行就跳到最近的相邻实心格）
- **服务器日志 only**：所有运行信息输出到控制台，不进游戏聊天框

## 通关路线（目标）

```
木质工具 → 石工具 → 铁工具 → 钻石 → 黑曜石→下界传送门 → 
下界（岩浆桥/烈焰棒/末影珍珠）→ 要塞 → 末地 → 击败末影龙
```

目前已实现：工具合成链、挖矿（surface + cave + strip）、冶炼、下界传送门浇铸、黑曜石桥、下界要塞寻路、末地要塞定位、末影龙战斗。

## License

MIT

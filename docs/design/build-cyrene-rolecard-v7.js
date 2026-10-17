#!/usr/bin/env node
/**
 * 昔涟角色包 v7 生成器（ADR-052 / TASK-20260913-002）。
 *
 * 职责：把 `docs/design/cyrene-rolecard-v7.md` 里以 `<!--FIELD:xxx-->...<!--/FIELD:xxx-->`
 * 标记的 persona 文本，与既有 `.rolepack` 中的 worldbook / style / voice / expression / memory
 * 层合并，产出可导入的 `cyrene.rolepack`。
 *
 * 设计约束：
 * - **单一事实来源**：persona 文本只维护在 md 里，禁止手改 JSON（JSON 由本脚本生成）。
 * - **不触碰非 persona 层**：worldbook 等原样透传，保证"不破坏基础功能"。
 * - **可复现**：同输入产出同字节（2 空格缩进、UTF-8 无 BOM、末尾换行）。
 *
 * 用法：
 *   node docs/design/build-cyrene-rolecard-v7.js            # 生成
 *   node docs/design/build-cyrene-rolecard-v7.js --check    # 只校验，不写盘（CI/自检用）
 */

'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const SRC = path.join(ROOT, 'docs', 'design', 'cyrene-rolecard-v7.md');
const PACK = path.join(ROOT, 'cyrene.rolepack');
const OUT_VERSION = '7.0.0';

/** 从 markdown 中取出一个 FIELD 区块的正文（已 trim）。缺失即抛，避免静默产出空字段。 */
function field(md, name) {
  const start = `<!--FIELD:${name}-->`;
  const end = `<!--/FIELD:${name}-->`;
  const i = md.indexOf(start);
  const j = md.indexOf(end);
  if (i < 0 || j < 0 || j < i) {
    throw new Error(`角色卡源缺少 FIELD 区块：${name}`);
  }
  return md.slice(i + start.length, j).trim();
}

/**
 * 把 FIELD 正文按 markdown 列表解析为字符串数组（只取顶层 `- ` 项）。
 *
 * guardrail F6：原实现 `.filter(startsWith('- '))` 会把**写法出错的行静默丢弃**（例如把
 * `- 条目` 误写成 `* 条目`），条目数量下降却仍通过宽松阈值。现在遇到任何既非空行、又非
 * `- ` 列表项的行即 fail-fast——宁可拒绝生成，也不要静默少一条人格规则。
 */
function bullets(block) {
  const out = [];
  const offenders = [];
  block
    .split(/\r?\n/)
    .map((l) => l.trim())
    .forEach((l) => {
      if (!l) return;
      if (!l.startsWith('- ')) {
        offenders.push(l.slice(0, 40));
        return;
      }
      const item = l.slice(2).trim();
      if (item) out.push(item);
    });
  if (offenders.length) {
    throw new Error(
      `列表项格式错误（必须以 "- " 开头，实际见下）：${offenders.map((s) => JSON.stringify(s)).join('、')}`
    );
  }
  return out;
}

/** 非 persona 层的**不变量**（guardrail F6：原实现只校验"数组非空"，等于没有护栏）。 */
const WORLD_ENTRIES = 40;
const WORLD_CHARS = 20750;
const WORLD_CONSTANT = 3;

function main() {
  const checkOnly = process.argv.includes('--check');

  const md = fs.readFileSync(SRC, 'utf8');

  // 非 persona 层从既有角色包原样透传（worldbook 40 条背景设定 / 风格 / 音色 / 表情 / 记忆）
  const prev = JSON.parse(fs.readFileSync(PACK, 'utf8').replace(/^\uFEFF/, ''));

  const systemPrompt = field(md, 'systemPrompt');
  const callSystemPrompt = field(md, 'callSystemPrompt');
  const address = field(md, 'address');
  const tone = bullets(field(md, 'tone'));
  const forbidden = bullets(field(md, 'forbidden'));

  const worldChars = (prev.worldbook || []).reduce((n, e) => n + (e.content || '').length, 0);
  const worldConstants = (prev.worldbook || []).filter((e) => e.constant === true).length;

  // guardrail R3 N1：**示例对话层自身的合规护栏**。
  // 背景：v7 首版示例里有两处 `（看完之后）` / `（这一步被系统拦下）` 式括号旁白，与
  // forbidden 第 1 条及 ROLE_STYLE_TAIL「不要描写自己的动作、神态或周围环境」**自相矛盾**，
  // 构成示范性污染（ac-verifier 实测 v7-flash 因此产出整段括号旁白）。仅在 PRD 里写要求
  // 没有机制约束 ⇒ 同类污染可静默回归。此处把要求变成**可失败的断言**。
  //
  // guardrail R4 F2 收紧：星号规则原为 `\*[^*\n]{2,}\*`，在 `**强调**` 上会从**第二个星**
  // 起匹配出 `*强调*` —— 把行内 Markdown 加粗误判为旁白（当前资产无此写法故未爆，但属误报源）。
  // 现先剥掉所有成对 `**`，再检测剩余的单个 `*…*`，语义与"承载动作的星号包裹"对齐。
  // ac-verifier R5 条件③：量词由 `{2,}` 收紧为 `{1,}` —— 单字包裹（`*笑*` / `（笑）`）
  // 同样是动作旁白写法，原量词使它们成为固定盲区。
  const hasStarNarration = (l) => /\*[^*\n]+\*/.test(l.replace(/\*\*/g, ''));
  const demoLines = (systemPrompt.match(/^\*\*(?:他|你)\*\*：.*$/gm) || []);
  const demoOffenders = demoLines.filter(
    (l) => /[（(][^）)]+[）)]/.test(l) || hasStarNarration(l)
  );
  // ac-verifier R5 条件①：**严格交替**（他、你、他、你…），而非仅"数量相等"——
  // 原实现只数条数，交换相邻回合顺序仍会通过，与注释声称的"严格交替"不符。
  const demoSeq = (systemPrompt.match(/^\*\*(他|你)\*\*：/gm) || [])
    .map((s) => s.replace(/^\*\*/, '').charAt(0));
  const demoAlternates = demoSeq.every((who, i) => who === (i % 2 === 0 ? '他' : '你'));
  // 已知覆盖缺口（guardrail R4 F2②，登记为 KL-11）：本护栏是**行级**的，只检查 `**他**：`
  // / `**你**：` 起始行；若把旁白写在续行或独立行则检不出。当前资产采用"一回合一行"的
  // 写法，故实际覆盖完整；若日后改写格式，需先做续行拼接再检测。

  // 结构自校验：任一字段空即 fail-fast（防止 md 标记写错导致整段静默丢失）
  // guardrail F6：阈值按 v7 实际规模收紧（原 `> 3000` 对万字级 persona 等于无约束，删掉 70% 仍过；
  // `>= 8` 对 11 条无感）。下限取"低于此值即说明整章被误删"的量级，而非精确字数（避免与资产版本耦合）。
  const checks = [
    ['systemPrompt', systemPrompt.length >= 8000],
    ['callSystemPrompt', callSystemPrompt.length >= 600],
    ['address', address.length >= 60],
    ['tone', tone.length >= 10],
    ['forbidden', forbidden.length >= 10],
    ['示例对话层条数', (systemPrompt.match(/^\*\*他\*\*：/gm) || []).length >= 8],
    // guardrail R3 N1 / ac-verifier R5 条件①：他/你 回合数量必须相等，**且严格交替**。
    ['示例对话层配对', (systemPrompt.match(/^\*\*他\*\*：/gm) || []).length ===
      (systemPrompt.match(/^\*\*你\*\*：/gm) || []).length],
    ['示例对话层严格交替', demoAlternates],
    ['示例对话层无旁白', demoOffenders.length === 0],
    ['对照示范层', (systemPrompt.match(/✗/g) || []).length >= 5],
    ['昔涟式占有小节', systemPrompt.includes('想占住一个位置')],
    ['能力可见性清单', systemPrompt.includes('你手上有什么')],
    ['worldbook 条数不变量', (prev.worldbook || []).length === WORLD_ENTRIES],
    ['worldbook 字数不变量', worldChars === WORLD_CHARS],
    ['worldbook 恒定条数不变量', worldConstants === WORLD_CONSTANT],
  ];
  const failed = checks.filter(([, ok]) => !ok).map(([n]) => n);
  if (failed.length) {
    throw new Error(`角色卡自校验失败：${failed.join('、')}`);
  }

  const pack = {
    schema: prev.schema,
    id: prev.id,
    name: prev.name,
    version: OUT_VERSION,
    persona: { systemPrompt, callSystemPrompt, address, tone, forbidden },
    worldbook: prev.worldbook,
    style: prev.style,
    voice: prev.voice,
    expression: prev.expression,
    memory: prev.memory,
  };

  const json = JSON.stringify(pack, null, 2) + '\n';

  const stat = {
    version: pack.version,
    systemPrompt: systemPrompt.length,
    callSystemPrompt: callSystemPrompt.length,
    address: address.length,
    tone: tone.length,
    forbidden: forbidden.length,
    worldbook: pack.worldbook.length,
    bytes: Buffer.byteLength(json, 'utf8'),
  };

  if (checkOnly) {
    // guardrail F6：`--check` 原先只做"能否生成"，**从不与磁盘上的产物比较** —— 资产被手改
    // 或过期时它照样 exit 0，"通过"≠"一致"。现在重算结果必须与磁盘逐字节相同，否则非零退出。
    const onDisk = fs.existsSync(PACK) ? fs.readFileSync(PACK, 'utf8') : null;
    if (onDisk === null) {
      console.error('[check] 失败：磁盘上不存在 ' + PACK);
      process.exit(2);
    }
    if (onDisk !== json) {
      const a = Buffer.from(onDisk, 'utf8');
      const b = Buffer.from(json, 'utf8');
      console.error(
        `[check] 失败：磁盘产物与源文本重算结果不一致（磁盘 ${a.length} B / 重算 ${b.length} B）。` +
          '请重跑不带 --check 的生成，或确认 md 源文本是否为最新。'
      );
      process.exit(2);
    }
    // guardrail R2 F13（**语义边界声明**）：本回比能证明"persona 层与 md 源文本一致"，但
    // **不能**证明透传层（worldbook / style / voice / expression / memory）未被篡改——它们是从
    // 产物本身读回再写回，与自身比较恒等；等长改动同样逃过检出。故文案如实收窄，避免读者
    // 把"一致"误读为"未被改动"。透传层的完整性由 Kotlin 侧断言负责
    // （AcVerifierRolePackV6Test 的 40 条 / 20,750 字 / 3 恒定）。
    console.log('[check] persona 层与源文本一致（透传层仅自洽，未做篡改检测）' + JSON.stringify(stat));
    return;
  }

  fs.writeFileSync(PACK, json, 'utf8');
  console.log('[ok] ' + PACK);
  console.log(JSON.stringify(stat, null, 2));
}

main();

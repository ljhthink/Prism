#!/usr/bin/env node
/**
 * Prism 文档一致性检查脚本
 * 用法: node scripts/consistency-check.js
 *
 * 检查项（CLAUDE.md 14.1）:
 *  1. README.md 文档索引中的每个相对链接指向的文件真实存在
 *     （例外：docs/reports/*.md 一次性工件可能被 .gitignore 排除，跳过存在性检查，
 *      仅检查命名规范，CLAUDE.md §20.1）
 *  2. docs/decisions/README.md 包含所有 docs/decisions/ADR-*.md
 *  3. docs/templates/README.md 包含所有 *-template.md
 *  4. docs/reports/ 中除 README.md 外的文件命名符合 YYYY-MM-DD-<task>-<type>.md
 *  5. 所有 .md 文件中不出现 file:/// 绝对路径（ADR-010，子 Agent 报告必须用相对路径）
 *  6. 所有 .md 文件中的相对链接 ../ 深度不超过 3 层
 *
 * 注：MCP 工具数一致性检查待 Prism 实现 Kotlin MCP server 后再加回
 *     （统计 @Tool 注解或 server.tool 调用数与文档宣称数一致）
 *
 * 退出码: 0=通过, 1=失败
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
let errors = [];

function listMarkdownFiles(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === 'dist' ||
          entry.name === '.git' || entry.name === 'target' ||
          entry.name === 'build' || entry.name === 'out' ||
          entry.name === '.gradle' || entry.name === '.idea' ||
          entry.name === '.trae') {
        continue;
      }
      out.push(...listMarkdownFiles(path.join(dir, entry.name)));
    } else if (entry.isFile() && entry.name.endsWith('.md')) {
      out.push(path.join(dir, entry.name));
    }
  }
  return out;
}

function rel(p) {
  return path.relative(ROOT, p).replace(/\\/g, '/');
}

function exists(rel) {
  return fs.existsSync(path.join(ROOT, rel));
}

// 1. README 相对链接检查
function checkReadmeLinks() {
  const readme = path.join(ROOT, 'README.md');
  if (!fs.existsSync(readme)) {
    errors.push('README.md 不存在');
    return;
  }
  const text = fs.readFileSync(readme, 'utf8');
  const linkRe = /\]\(([^)]+\.md[^)]*)\)/g;
  let m;
  while ((m = linkRe.exec(text)) !== null) {
    let link = m[1].split('#')[0].split('?')[0];
    if (/^https?:/.test(link)) continue;
    // CLAUDE.md §20.1: docs/reports/ 下的一次性工件（*.md，除 README.md 外）
    // 可能被 .gitignore 排除而不在 git 中，但 README 仍需链接它们作为本地索引。
    // 跳过这些链接的文件存在性检查，只检查命名规范（第 4 项检查负责）。
    if (/^docs\/reports\/[^/]+\.md$/.test(link) && link !== 'docs/reports/README.md') {
      continue;
    }
    if (!exists(link)) {
      errors.push(`README.md 链接指向不存在的文件: ${link}`);
    }
  }
}

// 2. decisions 索引检查
function checkDecisionsIndex() {
  const dir = path.join(ROOT, 'docs', 'decisions');
  const idx = path.join(dir, 'README.md');
  if (!fs.existsSync(dir)) return;
  const adrs = fs.readdirSync(dir).filter(f => /^ADR-\d+.*\.md$/.test(f) && f !== 'README.md');
  if (adrs.length === 0) return;
  if (!fs.existsSync(idx)) {
    errors.push('docs/decisions/ 存在 ADR 但缺少 README.md 索引');
    return;
  }
  const text = fs.readFileSync(idx, 'utf8');
  adrs.forEach(a => {
    if (!text.includes(a)) errors.push(`docs/decisions/README.md 未引用 ${a}`);
  });
}

// 3. templates 索引检查
function checkTemplatesIndex() {
  const dir = path.join(ROOT, 'docs', 'templates');
  const idx = path.join(dir, 'README.md');
  if (!fs.existsSync(dir)) return;
  const tpls = fs.readdirSync(dir).filter(f => /-template\.md$/.test(f));
  if (tpls.length === 0) return;
  if (!fs.existsSync(idx)) {
    errors.push('docs/templates/ 存在模板但缺少 README.md 索引');
    return;
  }
  const text = fs.readFileSync(idx, 'utf8');
  tpls.forEach(t => {
    if (!text.includes(t)) errors.push(`docs/templates/README.md 未引用 ${t}`);
  });
}

// 4. reports 命名检查
function checkReportsNaming() {
  const dir = path.join(ROOT, 'docs', 'reports');
  if (!fs.existsSync(dir)) return;
  const nameRe = /^\d{4}-\d{2}-\d{2}-.+\.md$/;
  fs.readdirSync(dir).forEach(f => {
    if (f === 'README.md') return;
    if (!f.endsWith('.md')) return;
    if (!nameRe.test(f)) {
      errors.push(`docs/reports/${f} 命名不符合 YYYY-MM-DD-<task>-<type>.md`);
    }
  });
}

// 5. file:/// 绝对路径检测（ADR-010）
function checkFileAbsolutePath() {
  const fileLinkRe = /\(file:\/\/\/[A-Za-z]/g;
  const inlineCodeRe = /`[^`\n]*`/g;
  const files = listMarkdownFiles(ROOT);
  for (const f of files) {
    const text = fs.readFileSync(f, 'utf8');
    const lines = text.split(/\r?\n/);
    let inCodeBlock = false;
    for (let i = 0; i < lines.length; i++) {
      const rawLine = lines[i];
      if (/^\s*(```|~~~)/.test(rawLine)) {
        inCodeBlock = !inCodeBlock;
        continue;
      }
      if (inCodeBlock) continue;
      const line = rawLine.replace(inlineCodeRe, '');
      let m;
      while ((m = fileLinkRe.exec(line)) !== null) {
        errors.push(`${rel(f)}:${i + 1} 出现 file:/// 绝对路径链接: ${rawLine.trim()}`);
      }
      fileLinkRe.lastIndex = 0;
    }
  }
}

// 6. 相对路径深度检测
function checkRelativePathDepth() {
  const MAX_DEPTH = 3;
  const linkRe = /\]\(([^)]+)\)/g;
  const files = listMarkdownFiles(ROOT);
  for (const f of files) {
    const text = fs.readFileSync(f, 'utf8');
    const lines = text.split(/\r?\n/);
    let inCodeBlock = false;
    for (let i = 0; i < lines.length; i++) {
      const rawLine = lines[i];
      if (/^\s*(```|~~~)/.test(rawLine)) {
        inCodeBlock = !inCodeBlock;
        continue;
      }
      if (inCodeBlock) continue;
      const line = rawLine.replace(/`[^`\n]*`/g, '');
      let m;
      while ((m = linkRe.exec(line)) !== null) {
        const link = m[1].split('#')[0].split('?')[0];
        if (/^(https?:|mailto:|#|\/)/.test(link)) continue;
        const depthMatches = link.match(/\.\.\//g);
        const depth = depthMatches ? depthMatches.length : 0;
        if (depth > MAX_DEPTH) {
          errors.push(`${rel(f)}:${i + 1} 相对路径 ../ 深度=${depth} 超过 ${MAX_DEPTH} 层: ${link}`);
        }
      }
      linkRe.lastIndex = 0;
    }
  }
}

checkReadmeLinks();
checkDecisionsIndex();
checkTemplatesIndex();
checkReportsNaming();
checkFileAbsolutePath();
checkRelativePathDepth();

if (errors.length) {
  console.error('一致性检查失败:');
  errors.forEach(e => console.error('  - ' + e));
  process.exit(1);
}
console.log('一致性检查通过 ✓');
process.exit(0);

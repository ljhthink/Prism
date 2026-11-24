/* Prism UI v2 · screens, router, sheets and interactions.
   Plain ES2017, no modules, so it opens from file:// without a server.
   Feature inventory mirrors the current app; nothing here invents a capability. */

(function () {
  "use strict";

  /* --------------------------------------------------------------- icons */

  var ICONS = {
    chat: '<path d="M4.5 6.5A2.5 2.5 0 0 1 7 4h10a2.5 2.5 0 0 1 2.5 2.5v6A2.5 2.5 0 0 1 17 15h-6l-4 3.5V6.5Z"/>',
    book: '<path d="M5 4.5h9a2 2 0 0 1 2 2v13H7a2 2 0 0 1-2-2v-13Z"/><path d="M8.5 8.5h4M8.5 12h4"/>',
    bolt: '<path d="M11.5 3 5 13h5l-1 8 7.5-10.5H11l.5-7.5Z"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M12 3v2.2M12 18.8V21M4.2 7.5l1.9 1.1M17.9 15.4l1.9 1.1M4.2 16.5l1.9-1.1M17.9 8.6l1.9-1.1"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    back: '<path d="M15 5l-7 7 7 7"/>',
    chev: '<path d="M9 6l6 6-6 6"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7"/>',
    close: '<path d="M6 6l12 12M18 6L6 18"/>',
    history: '<path d="M4 12a8 8 0 1 0 2.3-5.7"/><path d="M4 4.5V9h4.5"/><path d="M12 8v4.5l3 1.5"/>',
    edit: '<path d="M4.5 19.5h4l10-10a2.1 2.1 0 0 0-3-3l-10 10v3Z"/><path d="M14.5 6.5l3 3"/>',
    mic: '<rect x="9" y="4" width="6" height="10" rx="3"/><path d="M6 11a6 6 0 0 0 12 0M12 17v3"/>',
    globe: '<circle cx="12" cy="12" r="8"/><path d="M4 12h16M12 4a14 14 0 0 1 0 16 14 14 0 0 1 0-16Z"/>',
    brain: '<path d="M9 4.5A3 3 0 0 0 6 7.5a3 3 0 0 0-1.5 5.5A3 3 0 0 0 7 18a3 3 0 0 0 2 2V4.5Z"/><path d="M15 4.5A3 3 0 0 1 18 7.5a3 3 0 0 1 1.5 5.5A3 3 0 0 1 17 18a3 3 0 0 1-2 2V4.5Z"/>',
    phone: '<rect x="7" y="3" width="10" height="18" rx="2"/><path d="M11 18.5h2"/>',
    image: '<rect x="4" y="5" width="16" height="14" rx="2"/><circle cx="9" cy="10" r="1.5"/><path d="M4.5 16.5 9 12l4 4 3-2.5 3.5 3"/>',
    file: '<path d="M6 3.5h7l5 5v12H6z"/><path d="M13 3.5v5h5"/>',
    trash: '<path d="M5 7h14M9 7V4.5h6V7M7 7l1 13h8l1-13"/>',
    key: '<circle cx="8" cy="14" r="3.5"/><path d="M10.5 11.5 20 2M16 6l2.5 2.5M14 8l2.5 2.5"/>',
    shield: '<path d="M12 3.5 5 6v6c0 4.2 3 7.3 7 8.5 4-1.2 7-4.3 7-8.5V6l-7-2.5Z"/>',
    battery: '<rect x="3" y="8" width="16" height="8" rx="2"/><path d="M21 11v2"/>',
    refresh: '<path d="M20 12a8 8 0 1 1-2.3-5.7"/><path d="M20 4.5V9h-4.5"/>',
    qr: '<rect x="4" y="4" width="6" height="6"/><rect x="14" y="4" width="6" height="6"/><rect x="4" y="14" width="6" height="6"/><path d="M14 14h2v2h-2zM18 18h2v2h-2zM14 18h2M18 14h2"/>',
    power: '<path d="M12 3v9"/><path d="M6.5 6.5a8 8 0 1 0 11 0"/>',
    terminal: '<rect x="3" y="4.5" width="18" height="15" rx="2"/><path d="M7 9.5l3 2.5-3 2.5M12.5 15H17"/>',
    doc: '<path d="M6 3.5h7l5 5v12H6z"/><path d="M13 3.5v5h5M9 13h6M9 16.5h6"/>',
    clock: '<circle cx="12" cy="12" r="8"/><path d="M12 7.5V12l3.5 2"/>',
    tag: '<path d="M4 4h7l9 9-7 7-9-9V4Z"/><circle cx="8" cy="8" r="1.2"/>',
    layers: '<path d="m12 4 8 4-8 4-8-4 8-4Z"/><path d="m4 12 8 4 8-4M4 16l8 4 8-4"/>',
    microphone: '<rect x="9" y="4" width="6" height="10" rx="3"/><path d="M6 11a6 6 0 0 0 12 0M12 17v3"/>'
  };

  function icon(name, size) {
    size = size || 18;
    return (
      '<svg width="' + size + '" height="' + size + '" viewBox="0 0 24 24" fill="none" ' +
      'stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" ' +
      'aria-hidden="true">' + (ICONS[name] || "") + "</svg>"
    );
  }

  function esc(s) {
    return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;");
  }

  /* ---------------------------------------------------------------- state */

  var S = {
    screen: "chat-empty",
    dir: "push",
    providerId: "deepseek",
    providers: [
      { id: "deepseek", name: "DeepSeek V3", baseUrl: "https://api.deepseek.com", model: "deepseek-chat", vision: false, visionFallback: true },
      { id: "anthropic", name: "Anthropic", baseUrl: "https://api.anthropic.com", model: "claude-sonnet-4", vision: true, visionFallback: false },
      { id: "ollama", name: "本机 Ollama", baseUrl: "http://127.0.0.1:11434", model: "qwen2.5", vision: false, visionFallback: false }
    ],
    flags: {
      web: false,
      thinking: true,
      bgControl: false,
      vision: true,
      visionAuto: true,
      hidden: true,
      watch: false,
      selfHeal: true,
      webview: true,
      qqEnabled: false,
      qqRisk: false,
      qqSchedule: true,
      qqAutoReply: false,
      qqMemory: true,
      qqPacing: true,
      qqTools: true,
      qqPhone: false,
      qqDiary: true,
      qqWake: false,
      qqPoisson: true,
      qqCircuit: false,
      pet: false,
      petBubble: true,
      petLowPower: false,
      reduceMotion: false,
      usePoisson: true
    },
    qq: {
      transport: "协议端",
      host: "192.168.1.10:3000",
      token: "已保存 · 隐藏",
      selfId: "10001",
      targetId: "10000",
      contact: "我的主号",
      window: "07:00 – 22:00",
      dailyCap: 30,
      maxPerDay: 3,
      minInterval: 10,
      history: 20,
      toolBudget: 8,
      power: "已生效",
      status: "停",
      lastOutcome: "今天 09:14 已发送 · 1 条"
    },
    composer: {
      text: "",
      upload: false,
      attach: null,
      generating: false
    },
    mcp: {
      name: "",
      transport: "HTTP",
      baseUrl: "",
      token: "",
      headers: [["", ""]],
      enabled: true
    },
    capabilityTab: "mcp",
    kb: "产品复盘",
    history: [
      ["2026 Q3 产品复盘 · 留存归因", "今天 19:04", 12, "DeepSeek V3"],
      ["把周报整理成三条结论", "今天 09:12", 8, "DeepSeek V3"],
      ["端侧 RAG 的召回率怎么评估", "昨天 21:40", 23, "Anthropic"],
      ["QQ 定时消息没发出去", "11-18 18:22", 6, "DeepSeek V3"],
      ["语音通话测试：角色音色", "11-16 20:05", 15, "本机 Ollama"]
    ],
    voice: {
      mode: "stream",
      phase: "listening",
      transcript: "帮我把今天的会议记成三条待办",
      hint: "免按压，说完稍停即发送"
    },
    sheet: null,
    toast: null
  };

  function provider() {
    return S.providers.filter(function (p) { return p.id === S.providerId; })[0] || S.providers[0];
  }

  function flag(key, fallback) {
    return S.flags[key] === undefined ? !!fallback : S.flags[key];
  }

  /* -------------------------------------------------------------- helpers */

  function statusbar() {
    return (
      '<div class="statusbar"><span>9:41</span><span class="sb-right">' +
      '<span class="sb-bar" style="height:4px"></span>' +
      '<span class="sb-bar" style="height:7px"></span>' +
      '<span class="sb-bar" style="height:9px"></span>' +
      '<span style="margin-left:6px">84</span></span></div>'
    );
  }

  function topbar(title, actions, sub) {
    return (
      '<header class="topbar"><h1>' + esc(title) + "</h1>" +
      (sub ? '<span class="sub">' + esc(sub) + "</span>" : "") +
      '<div class="actions">' + (actions || "") + "</div></header>"
    );
  }

  function detailHead(title, right) {
    return (
      '<header class="detail-head">' +
      '<button class="icon-btn" data-act="back" aria-label="返回">' + icon("back") + "</button>" +
      "<h1>" + esc(title) + "</h1>" +
      '<div class="right">' + (right || "") + "</div></header>"
    );
  }

  function tabbar(active) {
    var items = [
      ["chat", "对话", "chat"],
      ["knowledge", "知识", "book"],
      ["capabilities", "能力", "bolt"],
      ["settings", "设置", "settings"]
    ];
    return (
      '<nav class="tabbar">' +
      items.map(function (it) {
        return '<button class="tab" data-act="tab" data-screen="' + it[0] + '" aria-selected="' +
          (active === it[0]) + '">' + icon(it[2], 20) + "<span>" + it[1] + "</span></button>";
      }).join("") + "</nav>"
    );
  }

  function sw(key, label, fallback) {
    var on = flag(key, fallback);
    return '<label class="switch"><input type="checkbox" data-flag="' + key + '" aria-label="' +
      esc(label) + '"' + (on ? " checked" : "") + '><span class="track"></span></label>';
  }

  function row(o) {
    var tag = o.screen || o.sheet || o.act ? "button" : "div";
    var attrs = "";
    if (o.screen) attrs += ' data-act="go" data-screen="' + o.screen + '"';
    if (o.sheet) attrs += ' data-act="sheet" data-sheet="' + o.sheet + '"';
    if (o.act) attrs += ' data-act="' + o.act + '"';
    if (o.msg) attrs += ' data-msg="' + esc(o.msg) + '"';
    var tail = o.tail != null
      ? o.tail
      : o.switch
        ? sw(o.switch, o.title)
        : '<span class="chev">' + icon("chev", 16) + "</span>";
    return "<" + tag + ' class="row' + (o.cls ? " " + o.cls : "") + '"' + attrs + ">" +
      '<span class="row-main"><span class="row-title">' + esc(o.title) + "</span>" +
      (o.status ? '<span class="row-status' + (o.tone ? " " + o.tone : "") + '">' + esc(o.status) + "</span>" : "") +
      '</span><span class="row-tail">' + tail + "</span></" + tag + ">";
  }

  function kv(k, v) {
    return '<div class="kv"><span class="kv-key">' + esc(k) + '</span><span class="kv-val">' + v + "</span></div>";
  }

  function group(label, body) {
    return '<div class="group"><div class="group-label">' + esc(label) + "</div>" + body + "</div>";
  }

  function toastHost() {
    return '<div class="toast" id="toast"></div>';
  }

  /* ------------------------------------------------------------ composer */

  function composer(opts) {
    opts = opts || {};
    var text = opts.text !== undefined ? opts.text : S.composer.text;
    var generating = opts.generating !== undefined ? opts.generating : S.composer.generating;
    var attach = opts.attach !== undefined ? opts.attach : S.composer.attach;
    var chips = [
      ["web", "联网", "globe"],
      ["thinking", "思考", "brain"],
      ["bgControl", "后台", "phone"]
    ];
    return (
      '<div class="composer">' +
      (attach ? '<div class="attach-preview">' + icon("file", 14) + "<span>" + esc(attach) +
        '</span><button class="x" data-act="drop-attach" aria-label="移除">' + icon("close", 14) + "</button></div>" : "") +
      '<div class="cap-row">' +
      chips.map(function (c) {
        return '<button class="cap-chip" data-act="flag" data-flag="' + c[0] + '" aria-pressed="' +
          flag(c[0]) + '"><span class="led"></span>' + icon(c[2], 14) + "<span>" + c[1] + "</span></button>";
      }).join("") +
      "</div>" +
      '<div class="upload-bar' + (S.composer.upload ? " open" : "") + '">' +
      '<button data-act="attach-file">' + icon("file", 14) + "文件</button>" +
      '<button data-act="attach-image">' + icon("image", 14) + "相册</button>" +
      "</div>" +
      '<div class="composer-shell">' +
      '<button class="attach" data-act="toggle-upload" aria-expanded="' + (S.composer.upload ? "true" : "false") +
      '" aria-label="添加附件">' + icon("plus", 20) + "</button>" +
      '<div class="composer-field' + (text ? " has-text" : "") + '">' +
      '<textarea rows="1" aria-label="消息">' + esc(text) + "</textarea>" +
      '<span class="composer-ph">给 Prism 发消息</span></div>' +
      '<button class="send-btn" data-act="send"' + (generating ? ' data-stop="true"' : (text.trim() ? ' data-ready="true"' : "")) +
      ">" + (generating ? icon("close", 13) + "停止" : "发送") + "</button>" +
      "</div></div>"
    );
  }

  /* ---------------------------------------------------------------- chat */

  /* 顶栏只保留一行操作：左侧是端点 / 检索两个模式，右侧是通话、历史、新对话。
     标题「Prism」已按评审意见移除，把纵向空间留给消息。 */
  function chatHeader() {
    return statusbar() +
      '<header class="chat-head">' +
      '<button class="mode-chip" data-act="sheet" data-sheet="provider-list">' +
      icon("layers", 13) + '<span class="t">' + esc(provider().name) + "</span>" +
      '<span class="chev">' + icon("chev", 12) + "</span></button>" +
      '<button class="mode-chip" data-act="sheet" data-sheet="rag-mode">' +
      icon("book", 13) + '<span class="t">全库检索</span>' +
      '<span class="chev">' + icon("chev", 12) + "</span></button>" +
      '<span class="head-actions">' +
      '<button class="icon-btn" data-act="go" data-screen="voice-call" aria-label="语音通话">' + icon("mic") + "</button>" +
      '<button class="icon-btn" data-act="go" data-screen="history" aria-label="历史会话">' + icon("history") + "</button>" +
      '<button class="icon-btn" data-act="new-chat" aria-label="新对话">' + icon("edit") + "</button>" +
      "</span></header>";
  }

  function chatEmpty() {
    return chatHeader() +
      '<main class="scroll">' +
      '<div class="empty"><h2>还没有对话</h2><p>在下方输入问题，或从历史会话继续</p></div>' +
      "</main>" + composer({}) + tabbar("chat");
  }

  function trace(steps, live) {
    return '<div class="trace"><div class="trace-head">' +
      (live ? '<span class="dot live"></span>' : '<span class="dot ok"></span>') +
      "<span>" + (live ? "执行中" : "执行轨迹") + "</span>" +
      '<span class="mono" style="margin-left:auto">' + (live ? "1.8s" : "2.6s") + "</span></div>" +
      steps.map(function (s) {
        return '<div class="trace-step ' + (s[2] ? "live" : "") + '">' +
          '<span class="dot ' + (s[2] ? "live" : "ok") + '"></span>' + esc(s[0]) +
          '<span class="t">' + s[1] + "</span></div>";
      }).join("") + "</div>";
  }

  function chatRun() {
    return chatHeader() +
      '<main class="scroll">' +
      '<div class="chat-meta"><span class="mono">12 条</span><span>·</span><span>今天 19:04</span>' +
      '<span class="spacer"></span><span class="mono">上下文 62%</span></div>' +
      '<div class="msg-user">帮我看下《2026 Q3 产品复盘》里，留存下降的主要原因是什么？</div>' +
      '<div class="msg-ai"><div class="ai-mark">' + icon("bolt", 13) + "<span>Prism</span></div>" +
      trace([["思考", "0.9s"], ["知识库检索 · 3 段", "1.2s"], ["读取片段 · 第 12 页", "0.5s"]]) +
      '<div class="ai-body"><p>留存下降集中在 9 月第二周，新用户次日留存从 41.6% 降到 37.4%。文档里给了三条原因：</p>' +
      "<p>新手引导第 3 步流失、推送频次上调、9 月 8 日的登录改版。其中第一条影响最大，约占降幅的六成。</p></div>" +
      '<div class="sources"><div class="source"><span class="idx">[1]</span><span>2026-Q3-产品复盘.pdf</span>' +
      '<span class="meta">p.12</span></div><div class="source"><span class="idx">[2]</span><span>留存看板导出.xlsx</span>' +
      '<span class="meta">Sheet2</span></div></div></div>' +
      "</main>" + composer({}) + tabbar("chat");
  }

  /* LLM 执行过程：思考 → 工具调用 → 流式输出。
     每个阶段用 CSS 延迟依次入场；三颗点与光标持续动，表示真的在跑。 */
  function chatGenerating() {
    return chatHeader() +
      '<main class="scroll">' +
      '<div class="chat-meta"><span class="mono">13 条</span><span>·</span><span>刚刚</span>' +
      '<span class="spacer"></span><span class="run-tag"><span class="dot live"></span>运行中</span></div>' +
      '<div class="msg-user">把这份复盘整理成三条可执行的结论。</div>' +
      '<div class="msg-ai"><div class="ai-mark">' + icon("bolt", 13) + "<span>Prism</span></div>" +
      '<div class="trace run">' +
      '<div class="trace-head"><span class="dot live"></span><span>执行轨迹</span>' +
      '<span class="mono" style="margin-left:auto">已用 2.6s</span></div>' +
      '<div class="trace-step done r1"><span class="dot ok"></span>思考<span class="t">0.9s</span></div>' +
      '<div class="trace-step done r2"><span class="dot ok"></span>知识库检索 · 3 段<span class="t">1.2s</span></div>' +
      '<div class="trace-step live r3"><span class="dot live"></span>正在生成回答' +
      '<span class="dots"><i></i><i></i><i></i></span><span class="t">0.5s</span></div>' +
      "</div>" +
      '<div class="tool-card r2">' +
      '<div class="tool-head"><span class="mono">knowledge_base__search</span>' +
      '<span class="tool-state"><span class="ok-mark">' + icon("check", 11) + "</span>2.4s</span></div>" +
      '<div class="tool-line mono">query: 留存下降原因</div>' +
      '<div class="tool-line">返回 3 段 · 相似度 ≥ 0.62</div>' +
      "</div>" +
      '<div class="ai-body streaming">留存下降集中在 9 月第二周，新用户次日留存从 41.6% 降到 37.4%。' +
      '文档给出的第一条原因是新手引导第 3 步流失，约占降幅的六成<span class="caret"></span></div>' +
      "</div>" +
      "</main>" + composer({ generating: true }) + tabbar("chat");
  }

  /* ------------------------------------------------------- 会话历史（新增） */

  function history() {
    var items = S.history;
    var body = items.length === 0
      ? '<div class="empty"><div class="empty-mark">' + icon("history", 26) + "</div>" +
        "<h2>还没有历史会话</h2><p>开始一段对话后会出现在这里</p></div>"
      : '<div class="count-line">' + items.length + " 段会话 · 按最近更新排序</div>" +
        group("今天", items.slice(0, 2).map(historyRow).join("")) +
        (items.length > 2 ? group("更早", items.slice(2).map(historyRow).join("")) : "");
    return statusbar() + detailHead("历史会话",
      '<button class="icon-btn" data-act="new-chat" aria-label="新对话">' + icon("edit") + "</button>") +
      '<main class="scroll">' + body + "</main>" + tabbar("chat");
  }

  function historyRow(s) {
    return '<div class="row" data-act="open-session"><span class="row-main">' +
      '<span class="row-title">' + esc(s[0]) + "</span>" +
      '<span class="row-status">' + esc(s[1]) + " · " + s[2] + " 条 · " + esc(s[3]) + "</span></span>" +
      '<span class="row-tail"><button class="mini danger" data-act="del-session">删除</button></span></div>';
  }

  /* ------------------------------------------------------- 语音通话（新增） */

  function voiceCall() {
    var v = S.voice;
    var streaming = v.mode === "stream";
    var status = v.phase === "speaking"
      ? "播报中…（说「停」可打断）"
      : v.phase === "thinking"
        ? "思考中（工具调用照常执行）"
        : "聆听中…（说完稍停自动发送）";
    return (
      statusbar() +
      '<div class="voiceroom">' +
      '<header class="voice-head">' +
      '<div><div class="voice-title">语音通话</div>' +
      '<div class="voice-sub">' + esc(status) + "</div></div>" +
      '<button class="hangup" data-act="go" data-screen="chat-run" aria-label="挂断">' + icon("close", 20) + "</button>" +
      "</header>" +
      '<div class="voice-stage">' +
      '<div class="orb"><span class="ring r1"></span><span class="ring r2"></span>' +
      '<span class="orb-core">' + icon("mic", 34) + "</span></div>" +
      '<div class="voice-transcript">' + esc(v.transcript) + "</div>" +
      '<div class="wave">' + waveBars() + "</div>" +
      '<div class="voice-hint">' + esc(v.hint) + "</div>" +
      "</div>" +
      '<div class="voice-foot">' +
      (streaming
        ? '<button class="voice-mode" data-act="voice-mode">' +
          '<span class="dot live"></span>端侧流式 · 免按压</button>'
        : '<button class="hold-talk" data-act="voice-mode">按住说话</button>') +
      '<div class="voice-privacy">' +
      "端侧离线识别 · 音频不出设备、不落盘；播报使用本机系统 TTS" +
      "</div>" +
      "</div></div>"
    );
  }

  function waveBars() {
    var out = "";
    for (var i = 0; i < 28; i++) {
      var h = 6 + Math.round(Math.abs(Math.sin(i * 1.7)) * 22);
      out += '<i style="height:' + h + "px;animation-delay:" + (i * 55) + 'ms"></i>';
    }
    return out;
  }

  /* ----------------------------------------------------------- knowledge */

  var KB = {
    "产品复盘": {
      name: "产品复盘", docs: 42, chunks: "1,642",
      list: [
        ["2026-Q3-产品复盘.pdf", "PDF · 42 页 · 186 段", "PDF"],
        ["留存看板导出.xlsx", "XLSX · 6 个工作表 · 94 段", "XLSX"],
        ["9 月登录改版说明.md", "MD · 今天 09:12 · 21 段", "MD"]
      ]
    },
    "研究资料": {
      name: "研究资料", docs: 61, chunks: "2,104",
      list: [
        ["端侧 RAG 综述.pdf", "PDF · 68 页 · 312 段", "PDF"],
        ["嵌入模型对比.md", "MD · 11-18 · 48 段", "MD"],
        ["用户访谈纪要.docx", "DOCX · 11-17 · 126 段", "DOCX"]
      ]
    },
    "个人笔记": {
      name: "个人笔记", docs: 25, chunks: "460",
      list: [["阅读笔记.md", "MD · 11-12 · 86 段", "MD"], ["想法收集.txt", "TXT · 11-09 · 34 段", "TXT"]]
    }
  };

  function knowledge() {
    return statusbar() +
      topbar("知识", '<button class="text-action" data-act="sheet" data-sheet="import">导入</button>') +
      '<main class="scroll">' +
      '<div class="count-line">3 个库 · 128 份文档 · 4,206 段</div>' +
      group("库",
        '<button class="row" data-act="kb" data-kb="产品复盘"><span class="row-main">' +
        '<span class="row-title">产品复盘</span><span class="row-status">42 份文档 · 昨天 18:20</span></span>' +
        '<span class="row-tail"><span class="mono" style="font-size:12px">1,642 段</span>' +
        '<span class="chev">' + icon("chev", 16) + "</span></span></button>" +
        kbRow("研究资料", "61 份文档 · 11-18", "2,104 段") +
        kbRow("个人笔记", "25 份文档 · 11-12", "460 段") +
        '<button class="row" data-act="sheet" data-sheet="create-kb"><span class="row-main">' +
        '<span class="row-title" style="color:var(--accent)">创建知识库</span>' +
        '<span class="row-status">新建一个分库</span></span>' +
        '<span class="row-tail"><span class="chev">' + icon("plus", 16) + "</span></span></button>"
      ) +
      '<div class="group"><div class="group-label">默认库</div>' +
      '<button class="row" data-act="kb" data-kb="个人笔记"><span class="row-main">' +
      '<span class="row-title">未分类文档</span><span class="row-status">未归入任何分库</span></span>' +
      '<span class="row-tail"><span class="mono" style="font-size:12px">21 段</span>' +
      '<span class="chev">' + icon("chev", 16) + "</span></span></button></div>" +
      '<div class="footnote">支持 PDF / DOCX / XLSX / PPTX / MD / TXT / CSV；导入后自动解析、分片、向量化。</div>' +
      "</main>" + tabbar("knowledge");
  }

  function kbRow(name, status, count) {
    return '<button class="row" data-act="kb" data-kb="' + esc(name) + '"><span class="row-main">' +
      '<span class="row-title">' + esc(name) + "</span>" +
      '<span class="row-status">' + esc(status) + "</span></span>" +
      '<span class="row-tail"><span class="mono" style="font-size:12px">' + count + "</span>" +
      '<span class="chev">' + icon("chev", 16) + "</span></span></button>";
  }

  function kbManage() {
    var kb = KB[S.kb] || KB["产品复盘"];
    return statusbar() +
      detailHead(kb.name, '<button class="icon-btn" data-act="sheet" data-sheet="import" aria-label="导入">' + icon("plus") + "</button>") +
      '<main class="scroll">' +
      '<div class="count-line">' + kb.docs + " 份文档 · " + kb.chunks + " 段 · 最近索引 今天 18:40</div>" +
      group("文档", kb.list.map(function (d) {
        return '<div class="row"><span class="row-main"><span class="row-title">' + esc(d[0]) + "</span>" +
          '<span class="row-status">' + esc(d[1]) + "</span></span>" +
          '<span class="row-tail"><button class="mini" data-act="sheet" data-sheet="doc-content">查看</button>' +
          '<button class="mini" data-act="toast" data-msg="选择目标库后移动">移动</button>' +
          '<button class="mini danger" data-act="toast" data-msg="需要二次确认">删除</button></span></div>';
      }).join("")) +
      '<div class="actions-row"><button class="btn btn-ghost btn-sm" data-act="sheet" data-sheet="import">' +
      icon("plus", 14) + "导入文档</button>" +
      '<button class="btn btn-ghost btn-sm" data-act="sheet" data-sheet="text-note">文本笔记</button></div>' +
      '<button class="danger-row" data-act="toast" data-msg="需要二次确认">删除这个库</button>' +
      "</main>";
  }

  /* -------------------------------------------------------- capabilities */

  function capabilities() {
    var tab = S.capabilityTab;
    var action = tab === "mcp"
      ? '<button class="icon-btn" data-act="sheet" data-sheet="mcp-config" aria-label="新建 Server">' + icon("plus") + "</button>"
      : tab === "skills"
        ? '<button class="text-action" data-act="sheet" data-sheet="skill-install">安装</button>'
        : "";
    var body = tab === "skills" ? skillsPanel() : tab === "memory" ? memoryPanel() : mcpPanel();
    return statusbar() + topbar("能力", action) +
      '<div class="segmented">' +
      seg("mcp", "MCP") + seg("skills", "技能") + seg("memory", "记忆") +
      '<span class="seg-line" id="seg-line"></span></div>' +
      '<main class="scroll">' + body + "</main>" + tabbar("capabilities");
  }

  function seg(id, label) {
    return '<button class="seg" role="tab" data-act="cap-tab" data-cap="' + id + '" aria-selected="' +
      (S.capabilityTab === id) + '">' + label + "</button>";
  }

  function mcpPanel() {
    var local = [
      ["文件系统", "读写授权目录", true],
      ["网页抓取", "正文提取", true],
      ["记忆", "读写 Prism 记忆", true],
      ["时序推理", "多步推演", false],
      ["时间", "时区换算", true]
    ];
    var remote = [
      ["GitHub", "https://api.githubcopilot.com/mcp", "未配置", false],
      ["Context7", "https://mcp.context7.com/mcp", "已连接 · 3", true]
    ];
    return '<div class="count-line">7 个服务 · 5 个已连接</div>' +
      group("本地内置", local.map(function (s) {
        return '<div class="row"><span class="row-main"><span class="row-title svc-name">' +
          '<span class="dot ' + (s[2] ? "ok" : "") + '"></span>' + s[0] + "</span>" +
          '<span class="row-status svc-transport">' + s[1] + "</span></span>" +
          '<span class="row-tail">' + sw("mcp_" + s[0], s[0], s[2]) + "</span></div>";
      }).join("")) +
      group("远程", remote.map(function (s) {
        return '<div class="row"><span class="row-main">' +
          '<span class="row-title svc-name"><span class="dot ' + (s[3] ? "ok" : "") + '"></span>' + s[0] + "</span>" +
          '<span class="row-status svc-transport">' + s[1] + "</span></span>" +
          '<span class="row-tail"><span class="row-status" style="font-size:11px">' + s[2] + "</span>" +
          '<button class="mini" data-act="sheet" data-sheet="mcp-config">配置</button>' +
          sw("mcp_" + s[0], s[0], s[3]) + "</span></div>";
      }).join("")) +
      group("从预设添加",
        ["Notion", "Sentry", "Stripe", "Slack"].map(function (n) {
          return '<button class="row" data-act="toast" data-msg="打开 ' + n + ' 配置">' +
            '<span class="row-main"><span class="row-title">' + n + "</span>" +
            '<span class="row-status">远程模板 · 填入 Key 后添加</span></span>' +
            '<span class="row-tail"><span class="chev">' + icon("chev", 16) + "</span></span></button>";
        }).join(""));
  }

  function skillsPanel() {
    var skills = [
      ["网页摘要", "内置 · 把长网页压成要点", true],
      ["表格整理", "内置 · 截图表格转 CSV", true],
      ["翻译", "本地 · 中英互译，保留术语", true],
      ["会议纪要", "远程 · 从文字里抽出决议与待办", false]
    ];
    return '<div class="count-line">4 个技能 · 3 个启用</div>' +
      group("技能", skills.map(function (s) {
        return '<div class="row"><span class="row-main"><span class="row-title">' + s[0] + "</span>" +
          '<span class="row-status">' + s[1] + "</span></span>" +
          '<span class="row-tail"><button class="mini" data-act="sheet" data-sheet="skill-detail">详情</button>' +
          sw("skill_" + s[0], s[0], s[2]) + "</span></div>";
      }).join("")) +
      '<div class="actions-row"><button class="btn btn-ghost btn-sm" data-act="sheet" data-sheet="skill-install">' +
      icon("plus", 14) + "从 URL 安装</button></div>" +
      '<div class="footnote">技能启动时自动扫描；安装前校验 https、Content-Type 与 10MB 上限。</div>';
  }

  function memoryPanel() {
    return group("会话内",
      '<div class="card"><div class="card-head"><span class="card-title">滑动窗口</span>' +
      '<span class="mono" style="font-size:12px;color:var(--dim)">10 轮</span></div>' +
      '<div class="card-note">超出后压缩成摘要并回注</div>' +
      '<div class="kv" style="margin-top:8px"><span class="kv-key">当前会话</span>' +
      '<span class="kv-val mono">12 条 · 占 62%</span></div>' +
      '<button class="mini" data-act="sheet" data-sheet="window-size">修改 N</button></div>') +
      group("跨会话",
        '<div class="card"><div class="card-head"><span class="card-title">已记住的主题</span>' +
        '<span class="mono" style="font-size:12px;color:var(--dim)">38 条</span></div>' +
        memRow("产品复盘的结论偏好", "3 天前召回") +
        memRow("写作时不用感叹号", "11-18 召回") +
        memRow("周四晚不在线", "11-16 召回") + "</div>") +
      group("用户画像",
        '<div class="card"><div class="kv"><span class="kv-key">显式</span>' +
        '<span class="kv-val">6 条 · 你填写的偏好</span></div>' +
        '<div class="kv"><span class="kv-key">隐式</span><span class="kv-val">11 条 · 从对话抽取</span></div>' +
        '<div class="actions-row"><button class="btn btn-ghost btn-sm" data-act="sheet" data-sheet="profile-edit">' +
        "添加画像</button></div></div>" +
        '<button class="danger-row" data-act="sheet" data-sheet="clear-memory">清除全部记忆</button>');
  }

  function memRow(text, when) {
    return '<div class="kv"><span class="kv-val" style="text-align:left">' + esc(text) +
      '</span><span class="kv-key mono" style="font-size:11px">' + esc(when) + "</span></div>";
  }

  /* ------------------------------------------------------------ settings */

  function settings() {
    return statusbar() + topbar("设置") +
      '<main class="scroll">' +
      '<div class="card" style="display:flex;gap:12px;align-items:flex-start">' +
      '<span class="dot ok" style="margin-top:7px"></span>' +
      '<div style="min-width:0"><div style="font-size:15px">运行正常</div>' +
      '<div class="card-note">' + esc(provider().name) + " · 3 个知识库 · Shizuku 就绪</div></div>" +
      '<div style="margin-left:auto;text-align:right"><div class="mono" style="font-size:15px;color:var(--warn)">2</div>' +
      '<div class="card-note" style="margin-top:2px">项待配置</div></div></div>' +
      '<div class="group">' +
      row({ title: "模型与密钥", status: provider().name + " · 1 个端点已激活", screen: "settings-model" }) +
      row({ title: "助手行为", status: "深度思考开 · 视觉旁路已授权", screen: "settings-behavior" }) +
      row({ title: "手机操控", status: "无障碍已开启 · Shizuku 就绪", screen: "settings-phone" }) +
      row({ title: "QQ 小号", status: flag("qqEnabled") ? "已开启 · 协议端在线" : "已关闭", screen: "settings-qq", tone: flag("qqEnabled") ? "ok" : null }) +
      row({ title: "语音与形象", status: "系统 TTS · 角色包 1 个 · 桌宠关闭", screen: "settings-voice" }) +
      row({ title: "网络、设备与关于", status: "Tavily · FULL 档", screen: "settings-system" }) +
      "</div>" +
      '<div class="footnote">Prism 1.1 · Apache 2.0</div>' +
      "</main>" + tabbar("settings");
  }

  function settingsModel() {
    var p = provider();
    return statusbar() +
      detailHead("模型与密钥", '<button class="icon-btn" data-act="sheet" data-sheet="provider-edit" aria-label="新建">' + icon("plus") + "</button>") +
      '<main class="scroll">' +
      group("当前端点",
        '<div class="card">' +
        kv("名称", esc(p.name)) +
        kv("Base URL", '<span class="mono">' + esc(p.baseUrl) + "</span>") +
        kv("模型", '<span class="mono">' + esc(p.model) + "</span>") +
        kv("API Key", "已保存 · Keystore") +
        kv("激活", "是") +
        "</div>" +
        '<div class="btn-row"><button class="btn btn-primary" data-act="sheet" data-sheet="provider-list">切换端点</button>' +
        '<button class="btn btn-ghost" data-act="sheet" data-sheet="provider-edit">新建自定义</button></div>') +
      group("生成",
        row({ title: "深度思考", status: flag("thinking") ? "已开启 · 先推理再回答" : "已关闭", tail: sw("thinking", "深度思考") }) +
        row({ title: "思考强度", status: "高 · 更慢，通常更准", act: "toast", msg: "低 / 高 / 最高", tail: '<span class="row-status">高</span>' })) +
      group("密钥",
        row({ title: "API Key", status: "按端点保存 · Keystore 加密", act: "sheet", sheet: "api-key" }) +
        row({ title: "自定义请求头", status: "0 条", act: "sheet", sheet: "provider-edit" })) +
      '<button class="danger-row" data-act="toast" data-msg="需要二次确认">删除这个端点</button>' +
      "</main>";
  }

  function settingsBehavior() {
    return statusbar() + detailHead("助手行为") +
      '<main class="scroll">' +
      group("回答方式",
        row({ title: "AI 行为偏好", status: "关于我 + 如何回答", act: "sheet", sheet: "user-rules" }) +
        row({ title: "工具权限", status: "自动审批已信任工具", act: "sheet", sheet: "tool-approval" })) +
      group("识图（纯文本模型）",
        row({ title: "云端视觉旁路", status: flag("vision") ? "已授权 · DeepSeek V3" : "未授权", tail: sw("vision", "云端视觉旁路") }) +
        row({ title: "自动旁路", status: flag("visionAuto") ? "图片自动转成描述后回答" : "已关闭", tail: sw("visionAuto", "自动旁路") }) +
        '<div class="footnote">开启后图片会直传你标记为视觉旁路的端点；不支持的模型会提示降级。</div>') +
      group("记忆",
        row({ title: "跨会话记忆", status: "L2 启用 · 最近召回 3 条", screen: "capabilities" }) +
        row({ title: "用户画像", status: "显式 6 条 · 隐式 11 条", screen: "capabilities" })) +
      "</main>";
  }

  function settingsPhone() {
    return statusbar() + detailHead("手机操控") +
      '<main class="scroll">' +
      '<div class="status-strip">' +
      '<div class="status-cell"><span class="k">无障碍</span><span class="v"><span class="dot ok"></span>已开启</span></div>' +
      '<div class="status-cell"><span class="k">Shizuku</span><span class="v"><span class="dot ok"></span>就绪</span></div>' +
      '<div class="status-cell"><span class="k">悬浮窗</span><span class="v"><span class="dot warn"></span>未授权</span></div>' +
      "</div>" +
      group("权限与安全",
        row({ title: "无障碍服务", status: "已开启 · AI 可读屏并点击/滑动/输入", act: "toast", msg: "打开系统无障碍设置" }) +
        row({ title: "无障碍自动恢复", status: flag("selfHeal") ? "被系统关闭后自动重连" : "已关闭", tail: sw("selfHeal", "无障碍自动恢复") }) +
        row({ title: "高危操作拦截", status: "支付/密码/验证码硬拦截 · 发送/删除/拨号需确认", act: "toast", msg: "切换策略" })) +
      group("执行面",
        row({ title: "后台操控", status: "在副屏执行 · 主屏可继续使用", act: "toast", msg: "查看执行面说明" }) +
        row({ title: "隐藏执行面", status: flag("hidden") ? "已开启 · 优先 shell 隐藏虚拟屏" : "已关闭 · 兼容模式", tail: sw("hidden", "隐藏执行面") }) +
        row({ title: "观看执行", status: flag("watch") ? "已开启 · ≤2fps" : "已关闭", tail: sw("watch", "观看执行") }) +
        row({ title: "Shizuku", status: "就绪 · 手势/滑动/返回均可用", act: "toast", msg: "Shizuku 已就绪" }) +
        row({ title: "后台保活", status: "任务期间动态保持", act: "toast", msg: "已按任务保持" })) +
      '<div class="actions-row"><button class="btn btn-ghost btn-sm" data-act="toast" data-msg="打开系统设置">打开系统设置</button></div>' +
      "</main>";
  }

  function settingsQq() {
    var on = flag("qqEnabled");
    return statusbar() + detailHead("QQ 小号") +
      '<main class="scroll">' +
      '<div class="card" style="display:flex;align-items:center;gap:12px">' +
      '<span class="dot ' + (on ? "ok" : "") + '"></span>' +
      '<div><div style="font-size:15px">' + (on ? "已开启" : "已关闭") + "</div>" +
      '<div class="card-note">' + (on ? "协议端在线 · NapCat" : "未连接协议端") + "</div></div>" +
      '<div style="margin-left:auto">' + sw("qqEnabled", "QQ 小号助手") + "</div></div>" +
      '<div class="group"><div class="group-label">协议端管家</div>' +
      '<div class="card"><div class="kv"><span class="kv-key">状态</span><span class="kv-val">' +
      (on ? "已就绪 · QQ 在线" : "未启动") + "</span></div>" +
      '<div class="kv"><span class="kv-key">省电策略</span><span class="kv-val ' +
      (S.qq.power === "已生效" ? "" : "row-status warn") + '">' + S.qq.power + "</span></div>" +
      '<div class="actions-row">' +
      '<button class="btn btn-ghost btn-sm" data-act="toast" data-msg="已请求系统豁免">电量豁免</button>' +
      '<button class="btn btn-ghost btn-sm" data-act="toast" data-msg="已提交一键修复">一键修复</button>' +
      '<button class="btn btn-ghost btn-sm" data-act="qq-start">启动</button>' +
      '<button class="btn btn-ghost btn-sm" data-act="toast" data-msg="已提交重启">重启</button>' +
      '<button class="btn btn-ghost btn-sm" data-act="toast" data-msg="已提交停止">停止</button>' +
      '<button class="btn btn-ghost btn-sm" data-act="toast" data-msg="打开扫码页">扫码登录</button>' +
      '<button class="btn btn-ghost btn-sm" data-act="toast" data-msg="打开 Termux 权限">命令权限</button>' +
      "</div>" +
      '<div class="note-line">ZeroTermux 只需安装一次；掉线后由通知告警。</div></div></div>' +
      group("连接",
        row({ title: "传输通道", status: S.qq.transport + (on ? " · 当前生效" : " · 未连接时用无障碍兜底"), act: "toast", msg: "切换为" + (S.qq.transport === "协议端" ? "无障碍" : "协议端") }) +
        row({ title: "协议端地址", status: S.qq.host + " · token 已保存", act: "sheet", sheet: "qq-connection" }) +
        row({ title: "连接测试", status: on ? "HTTP 通 · WS 通 · 账号在线" : "未测试", act: "qq-test" })) +
      group("消息",
        row({ title: "定时与主动消息", status: flag("qqSchedule") ? "已开启 · 07:00–22:00 · 每天至多 3 次" : "已关闭", screen: "settings-qq-schedule" }) +
        row({ title: "自动回复", status: flag("qqAutoReply") ? "已开启 · 仅私聊" : "已关闭", screen: "settings-qq-reply" }) +
        row({ title: "表情库", status: "20 张 · 3 张待补描述", screen: "settings-qq-stickers" })) +
      (flag("qqCircuit")
        ? group("保护", row({ title: "已熔断", status: "连续失败已自动停止 · 点击恢复", act: "qq-reset", tone: "bad" }))
        : "") +
      group("最近状态",
        '<div class="card"><div class="kv" style="border-bottom:0"><span class="kv-key">上次执行</span>' +
        '<span class="kv-val" style="white-space:normal;text-align:right">' + esc(S.qq.lastOutcome) + "</span></div></div>") +
      '<button class="danger-row" data-act="toast" data-msg="关闭后停止一切自动化">关闭并清除配置</button>' +
      "</main>";
  }

  function settingsQqSchedule() {
    return statusbar() + detailHead("定时与主动消息") +
      '<main class="scroll">' +
      group("主动消息",
        row({ title: "随机定时主动消息", status: flag("qqSchedule") ? "已开启" : "已关闭", tail: sw("qqSchedule", "随机定时") }) +
        row({ title: "活跃时段", status: S.qq.window, act: "sheet", sheet: "qq-schedule-edit" }) +
        row({ title: "每日消息上限", status: S.qq.dailyCap + " 条 / 天（0 = 不限）", act: "sheet", sheet: "qq-schedule-edit" }) +
        row({ title: "每日定时上限", status: S.qq.maxPerDay + " 条", act: "sheet", sheet: "qq-schedule-edit" }) +
        row({ title: "最小间隔", status: S.qq.minInterval + " 分钟", act: "sheet", sheet: "qq-schedule-edit" }) +
        row({ title: "随机策略", status: flag("qqPoisson") ? "泊松 · 偶发成串" : "均匀 · 平缓随机", tail: sw("qqPoisson", "随机策略") }) +
        row({ title: "熄屏时点亮屏幕执行", status: flag("qqWake") ? "已开启" : "已关闭 · 熄屏时跳过并记录原因", tail: sw("qqWake", "熄屏点亮") })) +
      group("执行结果",
        '<div class="card"><div class="kv" style="border-bottom:0"><span class="kv-key">上次</span>' +
        '<span class="kv-val" style="white-space:normal;text-align:right">' + esc(S.qq.lastOutcome) + "</span></div></div>" +
        row({ title: "熔断状态", status: flag("qqCircuit") ? "已熔断（需手动恢复）" : "正常", tail: "<span class=\"row-status\">" + (flag("qqCircuit") ? "恢复" : "正常") + "</span>" })) +
      "</main>";
  }

  function settingsQqReply() {
    return statusbar() + detailHead("自动回复") +
      '<main class="scroll">' +
      group("回复",
        row({ title: "双向对话", status: flag("qqAutoReply") ? "已开启 · 你发消息后 AI 以小号身份回你" : "已关闭", tail: sw("qqAutoReply", "双向对话") }) +
        row({ title: "定时消息联系人", status: S.qq.contact, act: "sheet", sheet: "qq-contact" }) +
        row({ title: "历史上下文条数", status: "最近 " + S.qq.history + " 条", act: "sheet", sheet: "qq-history" })) +
      group("上下文",
        row({ title: "注入 Prism 记忆", status: flag("qqMemory") ? "已开启 · 参考跨会话记忆与画像" : "已关闭", tail: sw("qqMemory", "注入记忆") }) +
        row({ title: "拟人节奏", status: flag("qqPacing") ? "已开启 · 字/秒 + 思考时间，深夜更慢" : "已关闭 · 生成完立即发出", tail: sw("qqPacing", "拟人节奏") })) +
      group("能力",
        row({ title: "聊天里调用 Prism 能力", status: flag("qqTools") ? "已开启 · 联网/热榜/资料库/待办/文档/日记（上限 " + S.qq.toolBudget + "s）" : "已关闭", tail: sw("qqTools", "能力接入") }) +
        row({ title: "允许她在聊天里操作手机", status: flag("qqPhone") ? "已开启 · 后台建副屏（较高风险）" : "已关闭 · 不建副屏、不抢前台", tail: sw("qqPhone", "操作手机") }) +
        row({ title: "允许她写日记", status: flag("qqDiary") ? "已开启 · 本地保存，每天最多 3 条" : "已关闭", tail: sw("qqDiary", "写日记") })) +
      "</main>";
  }

  function settingsQqStickers() {
    var stickers = [
      ["u01", "开心", "昔涟眯眼笑，举着一颗星星"],
      ["u02", "抱抱", "昔涟张开双臂迎上来"],
      ["u03", "委屈", "眼角挂着泪，低头抿嘴"],
      ["u04", "思考", "托着下巴看向斜上方"],
      ["u05", "", "（还没有描述）"]
    ];
    return statusbar() +
      detailHead("表情库", '<button class="text-action" data-act="toast" data-msg="选择图片导入">导入</button>') +
      '<main class="scroll">' +
      '<div class="count-line">20 张 · 3 张待补描述 · 单张 ≤ 2MB</div>' +
      group("按语境挑选",
        stickers.map(function (s) {
          return '<div class="row"><span class="row-main">' +
            '<span class="row-title">' + esc(s[1] || "未标注") + " · " + s[0] + "</span>" +
            '<span class="row-status">' + esc(s[2]) + "</span></span>" +
            '<span class="row-tail"><button class="mini" data-act="sheet" data-sheet="sticker-desc">编辑</button>' +
            '<button class="mini danger" data-act="toast" data-msg="已删除">删除</button></span></div>';
        }).join("")) +
      '<div class="actions-row"><button class="btn btn-ghost btn-sm" data-act="toast" data-msg="选择图片导入">' +
      icon("plus", 14) + "导入表情</button></div>" +
      '<div class="footnote">描述越具体，她越能挑到贴切的一张；导入的图片只存在应用私有目录。</div>' +
      "</main>";
  }

  function settingsVoice() {
    return statusbar() + detailHead("语音与形象") +
      '<main class="scroll">' +
      group("识别",
        radioRow("端侧识别（默认）", "完全离线 · 音频不出设备", true) +
        radioRow("云端识别 · 阿里云百炼", "需 DashScope Key", false) +
        '<div class="field"><input id="dash-key" placeholder=" " autocomplete="off"><label for="dash-key">DashScope API Key</label></div>' +
        '<div class="actions-row"><button class="btn btn-ghost btn-sm" data-act="toast" data-msg="已保存 Key">保存 Key</button>' +
        '<button class="btn btn-ghost btn-sm" data-act="toast" data-msg="识别自检通过">自检</button></div>') +
      group("播报",
        radioRow("系统播报（默认）", "零依赖 · 本机 TTS", true) +
        radioRow("云端播报 · MiniMax", "需 MiniMax Key 与 GroupId", false) +
        radioRow("本地音色 · 离线", "需先导入 .voicepack", false) +
        row({ title: "本地音色档位", status: "质量优先", act: "toast", msg: "切换为速度优先" }) +
        row({ title: "播报方式", status: "连续播报（首声最快）", act: "toast", msg: "切换为整段播报" }) +
        row({ title: "语音热词", status: "12 条 · 纠正识别", act: "toast", msg: "编辑热词" })) +
      group("本地音色包",
        '<div class="card"><div class="kv"><span class="kv-key">已安装</span><span class="kv-val">2 个</span></div>' +
        '<div class="kv"><span class="kv-key">当前使用</span><span class="kv-val">昔涟 · speed 0.8</span></div>' +
        '<div class="actions-row"><button class="btn btn-ghost btn-sm" data-act="toast" data-msg="选择 .voicepack 文件">' +
        "导入音色包</button></div></div>") +
      group("角色",
        row({ title: "角色包", status: "已导入 1 个 · 生效中", screen: "settings-persona" }) +
        row({ title: "桌宠", status: flag("pet") ? "已开启 · 桌面悬浮" : "已关闭", screen: "settings-persona" })) +
      "</main>";
  }

  function radioRow(title, sub, on) {
    return '<button class="option" aria-selected="' + on + '"><span class="ring"></span>' +
      "<span><span class=\"name\">" + esc(title) + '</span><span class="url">' + esc(sub) + "</span></span>" +
      '<span class="check">' + icon("check", 16) + "</span></button>";
  }

  function settingsPersona() {
    return statusbar() + detailHead("角色与桌宠") +
      '<main class="scroll">' +
      group("角色包",
        '<div class="card"><div class="kv"><span class="kv-key">已导入</span><span class="kv-val">1 个 · 昔涟 v7</span></div>' +
        '<div class="kv"><span class="kv-key">内容</span><span class="kv-val">人设 / 世界书 / 风格 / 音色</span></div>' +
        '<div class="actions-row"><button class="btn btn-ghost btn-sm" data-act="toast" data-msg="选择 .json 角色包">' +
        '导入角色包</button><button class="btn btn-ghost btn-sm" data-act="toast" data-msg="已删除">移除</button></div></div>' +
        '<div class="footnote">角色包为本地 JSON（prism-rolepack/1），存在应用私有目录，随卸载清除。</div>') +
      group("桌宠",
        row({ title: "显示桌宠", status: flag("pet") ? "已开启" : "已关闭", tail: sw("pet", "显示桌宠") }) +
        row({ title: "悬浮窗权限", status: "未授权 · 显示桌宠需要", tone: "warn", act: "toast", msg: "打开系统授权页" }) +
        row({ title: "桌宠形象", status: "1 个 · Live2D", act: "toast", msg: "选择形象" }) +
        row({ title: "大小", status: "中", act: "toast", msg: "调整大小" }) +
        row({ title: "不透明度", status: "90%", act: "toast", msg: "调整不透明度" }) +
        row({ title: "表情回正", status: "3s 后恢复默认", act: "toast", msg: "调整回正时间" }) +
        row({ title: "待机小动作", status: "没人理时偶尔动一下", tail: sw("petIdle", "待机小动作", true) }) +
        row({ title: "减少动效", status: "停止待机动作与呼吸", tail: sw("reduceMotion", "减少动效") }) +
        row({ title: "显示对话气泡", status: "回复时在形象旁显示文字", tail: sw("petBubble", "对话气泡", true) }) +
        row({ title: "低功耗模式", status: "15fps · 省电优先", tail: sw("petLowPower", "低功耗") }) +
        '<div class="actions-row"><button class="btn btn-ghost btn-sm" data-act="toast" data-msg="位置已重置">重置位置</button></div>' +
        '<div class="note-line">形象资产保存在 filesDir/pets/，不上传。</div>') +
      "</main>";
  }

  function settingsSystem() {
    return statusbar() + detailHead("网络、设备与关于") +
      '<main class="scroll">' +
      group("搜索",
        row({ title: "搜索引擎增强", status: "Tavily 已配置 · 首选 Tavily", act: "sheet", sheet: "search" }) +
        row({ title: "WebView 渲染抓取", status: flag("webview") ? "已开启 · 仅公网 https" : "已关闭", tail: sw("webview", "WebView 抓取") })) +
      group("设备",
        row({ title: "性能档位", status: "FULL · 6GB 以上 · 自动检测", act: "sheet", sheet: "tier" })) +
      group("关于",
        row({ title: "版本", status: "1.1 · 2026-11-20", tail: '<span class="row-status mono" style="font-size:12px">1.1</span>' }) +
        row({ title: "开源许可", status: "Apache 2.0", act: "toast", msg: "查看许可" })) +
      "</main>";
  }

  /* --------------------------------------------------------------- sheets */

  function sheetShell(title, sub, body, footer) {
    return '<div class="scrim" data-act="close-sheet"></div><div class="sheet" role="dialog" aria-modal="true">' +
      '<div class="handle"></div><h2>' + esc(title) + "</h2>" +
      (sub ? '<div class="sheet-sub">' + esc(sub) + "</div>" : "") +
      '<div class="sheet-body">' + body + "</div>" + (footer || "") + "</div>";
  }

  var SHEETS = {
    "provider-list": function () {
      return sheetShell("切换端点", "已配置 " + S.providers.length + " 个端点 · 点击激活", S.providers.map(function (p) {
        return '<button class="option" data-act="pick-provider" data-id="' + p.id + '" aria-selected="' +
          (p.id === S.providerId) + '"><span class="ring"></span>' +
          '<span><span class="name">' + esc(p.name) + '</span><span class="url">' + esc(p.baseUrl) +
          " · " + esc(p.model) + "</span></span>" +
          '<span class="check">' + icon("check", 16) + "</span></button>";
      }).join("") +
        '<div class="btn-row"><button class="btn btn-ghost btn-block" data-act="sheet" data-sheet="provider-edit">' +
        "＋ 新建自定义 Provider</button></div>",
        '<div class="note-line">从预设添加：OpenAI 兼容 / Anthropic / Ollama / Moonshot / OpenRouter</div>');
    },

    "provider-edit": function () {
      var p = { name: "", baseUrl: "", model: "" };
      return sheetShell("新建自定义 Provider", "名称、地址与模型可随时修改",
        '<div class="field"><input id="pv-name" placeholder=" " autocomplete="off"><label for="pv-name">名称</label></div>' +
        '<div class="field"><input id="pv-url" placeholder=" " autocomplete="off"><label for="pv-url">Base URL</label></div>' +
        '<div class="field"><input id="pv-model" placeholder=" " autocomplete="off"><label for="pv-model">模型（逗号分隔）</label></div>' +
        '<div class="field"><input id="pv-key" type="password" placeholder=" " autocomplete="off"><label for="pv-key">API Key</label></div>' +
        '<div class="group"><div class="group-label">高级</div>' +
        '<div class="header-row"><div class="field"><input id="h-name" placeholder=" "><label for="h-name">Header 名</label></div>' +
        '<div class="field"><input id="h-val" placeholder=" "><label for="h-val">值</label></div></div>' +
        '<button class="mini" data-act="toast" data-msg="已添加一行">＋ 添加请求头</button></div>' +
        '<div class="group"><div class="group-label">行为</div>' +
        '<div class="row"><span class="row-main"><span class="row-title">设为激活 Provider</span></span>' +
        '<span class="row-tail">' + sw("activateAfterSave", "激活", true) + "</span></div>" +
        '<div class="row"><span class="row-main"><span class="row-title">设为视觉旁路 Provider</span>' +
        '<span class="row-status">主模型不支持图片时，图片发往此端点</span></span>' +
        '<span class="row-tail">' + sw("visionFallback", "视觉旁路") + "</span></div>" +
        '<div class="row"><span class="row-main"><span class="row-title">支持视觉（多模态）</span>' +
        '<span class="row-status">截图直接以图片发送给该模型</span></span>' +
        '<span class="row-tail">' + sw("supportsVision", "支持视觉") + "</span></div></div>" +
        '<div class="note-line">Base URL 只接受 http(s)；请求头值含换行会被拒绝。</div>',
        '<div class="btn-row"><button class="btn btn-primary" data-act="toast" data-msg="已保存并激活">保存</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">取消</button></div>');
    },

    "api-key": function () {
      return sheetShell("API Key", "按端点分别保存 · Keystore 加密", S.providers.map(function (p) {
        return '<div class="option" aria-selected="' + (p.id === S.providerId) + '"><span class="ring"></span>' +
          '<span><span class="name">' + esc(p.name) + '</span><span class="url">' + esc(p.baseUrl) + "</span></span></div>";
      }).join("") +
        '<div class="field"><input id="api-key" type="password" placeholder=" " autocomplete="off">' +
        '<label for="api-key">新的 Key（留空则不变）</label></div>',
        '<div class="btn-row"><button class="btn btn-primary" data-act="toast" data-msg="已加密保存">保存</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">关闭</button></div>');
    },

    "user-rules": function () {
      return sheetShell("AI 行为偏好", "两个字段都会随每次请求发送，仅到你自配的端点",
        '<div class="field"><input id="ur-about" placeholder=" " autocomplete="off"><label for="ur-about">关于我</label></div>' +
        '<div class="note-line">如：我是 Python 开发者，主要做后端服务</div>' +
        '<div class="field" style="margin-top:20px"><input id="ur-resp" placeholder=" " autocomplete="off"><label for="ur-resp">如何回答</label></div>' +
        '<div class="note-line">如：用中文，简洁，先给结论再解释</div>',
        '<div class="btn-row"><button class="btn btn-primary" data-act="toast" data-msg="已保存">保存</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">取消</button></div>');
    },

    "tool-approval": function () {
      return sheetShell("工具权限", "决定工具调用是否需要你确认",
        radioRow("手动审批", "每次工具调用都询问", false) +
        radioRow("自动审批", "已信任工具直接执行，其余询问", true) +
        radioRow("禁用工具", "只对话，不调用任何工具", false) +
        '<div class="note-line">支付、密码、验证码属于硬拦截，任何模式下都不会执行。</div>',
        '<div class="btn-row"><button class="btn btn-primary" data-act="toast" data-msg="已保存">保存</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">取消</button></div>');
    },

    "mcp-config": function () {
      var m = S.mcp;
      return sheetShell("MCP Server", "本地内置零配置；远程需 Base URL",
        '<div class="field"><input id="mcp-name" placeholder=" " autocomplete="off"><label for="mcp-name">名称</label></div>' +
        '<div class="group"><div class="group-label">传输类型</div>' +
        '<div class="actions-row">' +
        ["SSE", "HTTP"].map(function (t) {
          return '<button class="mode-chip" data-act="mcp-transport" data-t="' + t + '" aria-pressed="' +
            (m.transport === t) + '">' + t + "</button>";
        }).join("") + "</div></div>" +
        '<div class="field"><input id="mcp-url" placeholder=" " autocomplete="off"><label for="mcp-url">Base URL</label></div>' +
        '<div class="field"><input id="mcp-token" type="password" placeholder=" " autocomplete="off">' +
        '<label for="mcp-token">Token / API Key</label></div>' +
        '<div class="group"><div class="group-label">自定义请求头</div>' +
        (m.headers || [["", ""]]).map(function (h, i) {
          return '<div class="header-row"><div class="field"><input placeholder=" " value="' + esc(h[0]) +
            '"><label>Header 名</label></div><div class="field"><input placeholder=" " value="' + esc(h[1]) +
            '"><label>值</label></div><button class="x" data-act="toast" data-msg="已移除">' + icon("close", 14) + "</button></div>";
        }).join("") +
        '<button class="mini" data-act="mcp-add-header">＋ 添加请求头</button></div>' +
        '<div class="row"><span class="row-main"><span class="row-title">启用该 Server</span>' +
        '<span class="row-status">保存后立即尝试握手</span></span><span class="row-tail">' +
        sw("mcpEnabled", "启用 Server", true) + "</span></div>",
        '<div class="btn-row"><button class="btn btn-primary" data-act="toast" data-msg="已保存配置">保存配置</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">取消</button></div>' +
        '<button class="danger-row" data-act="toast" data-msg="需要二次确认">删除 Server</button>');
    },

    "skill-install": function () {
      return sheetShell("从 URL 安装 Skill", "仅 https · Content-Type 白名单 · ≤ 10MB",
        '<div class="field"><input id="skill-url" placeholder=" " autocomplete="off">' +
        '<label for="skill-url">Skill URL</label></div>' +
        '<div class="note-line">https://example.com/skill.skill.md</div>' +
        '<div class="progress"><i style="width:38%"></i></div>' +
        '<div class="note-line">校验通过后解压到应用私有目录，恶意路径会被拒绝。</div>',
        '<div class="btn-row"><button class="btn btn-primary" data-act="toast" data-msg="安装中…">安装</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">取消</button></div>');
    },

    "skill-detail": function () {
      return sheetShell("翻译", "远程 · v1.2.0",
        '<div class="card">' +
        kv("来源", "HTTPS 安装") +
        kv("工具", "2 个") +
        kv("执行记录", "最近 10 次") +
        "</div>" +
        '<div class="group"><div class="group-label">工具</div>' +
        '<div class="row"><span class="row-main"><span class="row-title">translate_text</span>' +
        '<span class="row-status">中英互译，保留术语</span></span></div>' +
        '<div class="row"><span class="row-main"><span class="row-title">detect_language</span>' +
        '<span class="row-status">识别输入语言</span></span></div></div>' +
        '<div class="group"><div class="group-label">启用</div>' +
        '<div class="row"><span class="row-main"><span class="row-title">启用该 Skill</span></span>' +
        '<span class="row-tail">' + sw("skillEnabled", "启用", true) + "</span></div></div>",
        '<div class="btn-row"><button class="btn btn-ghost" data-act="close-sheet">关闭</button></div>' +
        '<button class="danger-row" data-act="toast" data-msg="需要二次确认">删除 Skill</button>');
    },

    import: function () {
      return sheetShell("导入内容", "解析 → 分片 → 向量化索引",
        '<div class="group" style="margin-top:0"><div class="group-label">目标知识库</div>' +
        '<div class="actions-row">' +
        ["默认库", "产品复盘", "研究资料"].map(function (n, i) {
          return '<button class="mode-chip" data-act="toast" data-msg="目标：' + n + '" aria-pressed="' + (i === 1) + '">' + n + "</button>";
        }).join("") + "</div></div>" +
        '<div class="group"><div class="group-label">来源</div>' +
        '<div class="row"><span class="row-main"><span class="row-title">选择文件</span>' +
        '<span class="row-status">PDF / DOCX / XLSX / PPTX / MD / TXT / CSV</span></span>' +
        '<span class="row-tail"><button class="mini" data-act="pick-file">选择</button></span></div>' +
        '<div class="row"><span class="row-main"><span class="row-title">文本笔记</span>' +
        '<span class="row-status">直接输入文字保存到库</span></span>' +
        '<span class="row-tail"><button class="mini" data-act="sheet" data-sheet="text-note">编辑</button></span></div></div>' +
        '<div class="progress"><i style="width:64%"></i></div>' +
        '<div class="count-line" style="padding-top:0">正在摄入：2026-Q3-产品复盘.pdf · 118/186 已嵌入</div>',
        '<div class="btn-row"><button class="btn btn-ghost" data-act="close-sheet">关闭</button></div>');
    },

    "text-note": function () {
      return sheetShell("文本笔记", "直接输入文字内容保存到知识库",
        '<div class="field"><input id="note-title" placeholder=" " autocomplete="off"><label for="note-title">标题</label></div>' +
        '<div class="field"><input id="note-body" placeholder=" " autocomplete="off"><label for="note-body">内容</label></div>',
        '<div class="btn-row"><button class="btn btn-primary" data-act="toast" data-msg="已保存并分片">保存</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">取消</button></div>');
    },

    "create-kb": function () {
      return sheetShell("创建知识库", "输入名称以新建分库",
        '<div class="field"><input id="kb-name" placeholder=" " autocomplete="off"><label for="kb-name">库名称</label></div>',
        '<div class="btn-row"><button class="btn btn-primary" data-act="toast" data-msg="已创建">创建</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">取消</button></div>');
    },

    "doc-content": function () {
      return sheetShell("2026-Q3-产品复盘.pdf", "按切片序号拼接 · 12,480 字符",
        '<div class="card" style="font-size:13px;line-height:1.7;color:var(--dim)">' +
        "第 1 段：九月第二周留存出现拐点，新用户次日留存 41.6% → 37.4%。\n\n" +
        "第 2 段：新手引导第 3 步（绑定模型端点）流失率 38%，是三条原因中影响最大的一条。\n\n" +
        "第 3 段：9 月 8 日登录改版让首屏多了一次跳转，次日留存再降 1.1 个百分点。</div>",
        '<div class="btn-row"><button class="btn btn-ghost" data-act="close-sheet">关闭</button></div>');
    },

    "window-size": function () {
      return sheetShell("滑动窗口大小", "保留最近 N 轮原始消息",
        '<div class="field"><input id="win-n" placeholder=" " inputmode="numeric" autocomplete="off"><label for="win-n">N（1–50）</label></div>' +
        '<div class="note-line">N 越大上下文越完整但更耗 token；超出部分自动摘要压缩。</div>',
        '<div class="btn-row"><button class="btn btn-primary" data-act="toast" data-msg="已保存">保存</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">取消</button></div>');
    },

    "clear-memory": function () {
      return sheetShell("清除全部记忆", "不可恢复",
        '<ul class="risk-list"><li>L2 跨会话记忆：38 条</li><li>L3 用户画像：17 条</li>' +
        "<li>L1 窗口大小配置保留（只清数据）</li></ul>",
        '<div class="btn-row"><button class="btn btn-danger" data-act="toast" data-msg="已清除">确认清除</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">取消</button></div>');
    },

    "profile-edit": function () {
      return sheetShell("添加画像", "显式偏好会随每次请求发送",
        '<div class="field"><input id="pf-key" placeholder=" " autocomplete="off"><label for="pf-key">标签</label></div>' +
        '<div class="field"><input id="pf-val" placeholder=" " autocomplete="off">' +
        '<label for="pf-val">偏好描述</label></div>',
        '<div class="btn-row"><button class="btn btn-primary" data-act="toast" data-msg="已保存">保存</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">取消</button></div>');
    },

    "rag-mode": function () {
      return sheetShell("知识库检索", "关闭后 AI 不会主动查资料",
        radioRow("关闭", "不使用知识库", false) +
        radioRow("全库", "在所有分库中检索", true) +
        radioRow("指定库 · 产品复盘", "只查这一个库", false) +
        '<div class="note-line">低端档位不提供该能力。</div>');
    },

    "qq-risk": function () {
      return sheetShell("开启 QQ 小号", "仅用于你自己的小号",
        '<ul class="risk-list">' +
        "<li>自动化操作可能触发平台限制，小号有被冻结的风险</li>" +
        "<li>请使用独立小号，不要用主号承载</li>" +
        "<li>消息只在设备、你自建的协议端与模型端点之间流动</li>" +
        "<li>内置低频节流与失败熔断，但不承诺零风险</li></ul>" +
        '<label class="checkline"><input type="checkbox" data-flag="qqRisk"><span>我已了解并承担上述风险</span></label>',
        '<button class="btn btn-primary btn-block" data-act="qq-enable"' + (flag("qqRisk") ? "" : " disabled") + ">开启</button>" +
        '<div class="btn-row"><button class="btn btn-ghost btn-block" data-act="close-sheet">取消</button></div>');
    },

    "qq-connection": function () {
      return sheetShell("协议端连接", "协议端需自建（NapCat / SnowLuma）",
        '<div class="field"><input id="qq-host" placeholder=" " autocomplete="off"><label for="qq-host">协议端地址</label></div>' +
        '<div class="note-line">填 PC 的局域网 IP，HTTP 默认 3000、WS 自动 3001；127.0.0.1 是手机自己。</div>' +
        '<div class="field"><input id="qq-token" type="password" placeholder=" " autocomplete="off">' +
        '<label for="qq-token">access_token（留空 = 保持不变）</label></div>' +
        '<div class="field"><input id="qq-self" placeholder=" " inputmode="numeric"><label for="qq-self">小号 QQ 号（self_id）</label></div>' +
        '<div class="field"><input id="qq-target" placeholder=" " inputmode="numeric"><label for="qq-target">目标（主号）QQ 号</label></div>' +
        '<div class="actions-row"><button class="btn btn-ghost btn-sm" data-act="qq-diagnose">连接诊断</button>' +
        '<button class="btn btn-ghost btn-sm" data-act="toast" data-msg="已标记清除令牌">清除令牌</button></div>',
        '<div class="btn-row"><button class="btn btn-primary" data-act="toast" data-msg="已保存并尝试连接">保存</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">取消</button></div>');
    },

    "qq-schedule-edit": function () {
      return sheetShell("定时参数", "24 小时制，允许跨零点",
        '<div class="field"><input id="qq-start" placeholder=" " autocomplete="off"><label for="qq-start">活跃时段起（HH:mm）</label></div>' +
        '<div class="field"><input id="qq-end" placeholder=" " autocomplete="off"><label for="qq-end">活跃时段止（HH:mm）</label></div>' +
        '<div class="actions-row">' +
        ["07:00–22:00", "08:00–23:00", "20:00–23:00"].map(function (w) {
          return '<button class="mode-chip" data-act="toast" data-msg="已填入 ' + w + '">' + w + "</button>";
        }).join("") + "</div>" +
        '<div class="field"><input id="qq-cap" placeholder=" " inputmode="numeric"><label for="qq-cap">每日消息上限（0–200，0 = 不限）</label></div>' +
        '<div class="field"><input id="qq-perday" placeholder=" " inputmode="numeric"><label for="qq-perday">每日定时上限（0–200）</label></div>' +
        '<div class="field"><input id="qq-interval" placeholder=" " inputmode="numeric"><label for="qq-interval">最小间隔（分钟，≥1）</label></div>',
        '<div class="btn-row"><button class="btn btn-primary" data-act="toast" data-msg="已保存">保存</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">取消</button></div>');
    },

    "qq-contact": function () {
      return sheetShell("定时消息联系人", "小号对你的备注名",
        '<div class="field"><input id="qq-contact" placeholder=" " autocomplete="off"><label for="qq-contact">备注名</label></div>',
        '<div class="btn-row"><button class="btn btn-primary" data-act="toast" data-msg="已保存">保存</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">取消</button></div>');
    },

    "qq-history": function () {
      return sheetShell("历史上下文条数", "回复时读取最近 N 条消息",
        '<div class="field"><input id="qq-hist" placeholder=" " inputmode="numeric"><label for="qq-hist">N（1–200）</label></div>' +
        '<div class="note-line">真正上限由字符预算兜底；消息与记忆会发送至模型端点。</div>',
        '<div class="btn-row"><button class="btn btn-primary" data-act="toast" data-msg="已保存">保存</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">取消</button></div>');
    },

    "sticker-desc": function () {
      return sheetShell("表情描述", "描述越具体，她越能挑到贴切的一张",
        '<div class="field"><input id="st-desc" placeholder=" " autocomplete="off"><label for="st-desc">描述</label></div>' +
        '<div class="field"><input id="st-emo" placeholder=" " autocomplete="off"><label for="st-emo">情绪标签</label></div>',
        '<div class="btn-row"><button class="btn btn-primary" data-act="toast" data-msg="已保存">保存</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">取消</button></div>');
    },

    search: function () {
      return sheetShell("搜索引擎增强", "按 博查 → 智谱 → SearXNG → Tavily → Bing/Baidu 顺序尝试",
        '<div class="field"><input id="s-tavily" type="password" placeholder=" " autocomplete="off">' +
        '<label for="s-tavily">Tavily API Key</label></div>' +
        '<div class="field"><input id="s-zhipu" type="password" placeholder=" " autocomplete="off">' +
        '<label for="s-zhipu">智谱 API Key</label></div>' +
        '<div class="field"><input id="s-searx" placeholder=" " autocomplete="off"><label for="s-searx">SearXNG 端点</label></div>' +
        '<div class="note-line">端点留空保存即清除配置；自建服务可达性由你自担。</div>',
        '<div class="btn-row"><button class="btn btn-primary" data-act="toast" data-msg="已保存">保存</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">取消</button></div>');
    },

    tier: function () {
      return sheetShell("性能档位", "按 RAM 自动分档，也可手动覆盖（重启生效）",
        radioRow("FULL · ≥6GB", "标准批次 · 嵌入常驻 · top-k 5", true) +
        radioRow("STANDARD · 4–6GB", "小批次 · 按需加载 · top-k 3", false) +
        radioRow("MINIMAL · 3–4GB", "关键词检索 · 不加载嵌入", false) +
        radioRow("CHAT_ONLY · <3GB", "仅聊天与 BYOK", false),
        '<div class="btn-row"><button class="btn btn-primary" data-act="toast" data-msg="已保存，重启生效">保存</button>' +
        '<button class="btn btn-ghost" data-act="close-sheet">取消</button></div>');
    }
  };

  /* ---------------------------------------------------------------- screens */

  var SCREENS = {
    "chat-empty": chatEmpty,
    "chat-run": chatRun,
    "chat-generating": chatGenerating,
    history: history,
    "voice-call": voiceCall,
    knowledge: knowledge,
    "kb-manage": kbManage,
    capabilities: capabilities,
    settings: settings,
    "settings-model": settingsModel,
    "settings-behavior": settingsBehavior,
    "settings-phone": settingsPhone,
    "settings-qq": settingsQq,
    "settings-qq-schedule": settingsQqSchedule,
    "settings-qq-reply": settingsQqReply,
    "settings-qq-stickers": settingsQqStickers,
    "settings-voice": settingsVoice,
    "settings-persona": settingsPersona,
    "settings-system": settingsSystem
  };

  /* ----------------------------------------------------------------- render */

  function tabOf(screen) {
    if (screen === "knowledge" || screen === "kb-manage") return "knowledge";
    if (screen === "capabilities") return "capabilities";
    if (screen.indexOf("settings") === 0) return "settings";
    return "chat";
  }

  function render(opts) {
    opts = opts || {};
    var app = document.getElementById("app");
    var screen = SCREENS[S.screen] ? S.screen : "chat-empty";
    app.innerHTML = SCREENS[screen]() + '<div id="sheet-host"></div>' + toastHost();
    app.classList.remove("enter", "push", "pop");
    if (opts.animate !== false) {
      void app.offsetWidth;
      app.classList.add("enter");
      if (S.dir === "push" || S.dir === "pop") app.classList.add(S.dir);
    }
    if (S.sheet) mountSheet(S.sheet, true);
    positionUnderline();
  }

  function positionUnderline() {
    var line = document.getElementById("seg-line");
    if (!line) return;
    var active = document.querySelector('.seg[aria-selected="true"]');
    if (!active) return;
    line.style.left = active.offsetLeft + "px";
    line.style.width = active.offsetWidth + "px";
  }

  function mountSheet(id, immediate) {
    var host = document.getElementById("sheet-host");
    if (!host) return;
    var build = SHEETS[id];
    if (!build) return;
    host.innerHTML = build();
    var open = function () {
      host.querySelector(".scrim").classList.add("open");
      host.querySelector(".sheet").classList.add("open");
    };
    if (immediate) requestAnimationFrame(open);
    else requestAnimationFrame(open);
    S.sheet = id;
  }

  function closeSheet() {
    var host = document.getElementById("sheet-host");
    if (!host || !S.sheet) return;
    var scrim = host.querySelector(".scrim");
    var sheet = host.querySelector(".sheet");
    if (scrim) scrim.classList.remove("open");
    if (sheet) sheet.classList.remove("open");
    S.sheet = null;
    setTimeout(function () { if (host && !S.sheet) host.innerHTML = ""; }, 320);
  }

  /* ---------------------------------------------------------------- router */

  function go(screen, dir) {
    var fromTab = tabOf(S.screen);
    var toTab = tabOf(screen);
    S.dir = dir || (fromTab !== toTab ? "rise" : "push");
    S.screen = screen;
    closeSheet();
    render({ animate: true });
    var sc = document.querySelector(".scroll");
    if (sc) sc.scrollTop = 0;
  }

  function back() {
    if (S.screen === "kb-manage") return go("knowledge", "pop");
    if (S.screen === "voice-call") return go("chat-run", "pop");
    if (S.screen === "history") return go("chat-run", "pop");
    if (S.screen === "settings-qq-schedule" || S.screen === "settings-qq-reply" ||
      S.screen === "settings-qq-stickers") return go("settings-qq", "pop");
    if (S.screen === "settings-persona") return go("settings-voice", "pop");
    if (S.screen.indexOf("settings-") === 0) return go("settings", "pop");
    return go("chat-run", "pop");
  }

  var toastTimer = null;
  function toast(msg) {
    var el = document.getElementById("toast");
    if (!el) return;
    el.textContent = msg;
    el.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.remove("show"); }, 1600);
  }

  function saveScrollAndRender() {
    var sc = document.querySelector(".scroll");
    var top = sc ? sc.scrollTop : 0;
    render({ animate: false });
    var sc2 = document.querySelector(".scroll");
    if (sc2) sc2.scrollTop = top;
    if (S.sheet) mountSheet(S.sheet, true);
  }

  /* -------------------------------------------------------------- handlers */

  function onClick(e) {
    var el = e.target.closest("[data-act]");
    if (!el) return;
    var act = el.getAttribute("data-act");

    if (act === "go" || act === "tab") {
      go(el.getAttribute("data-screen"));
    } else if (act === "back") {
      back();
    } else if (act === "sheet") {
      var id = el.getAttribute("data-sheet");
      if (id === "qq-risk") {
        if (flag("qqEnabled")) return;
        mountSheet("qq-risk", false);
        return;
      }
      mountSheet(id, false);
    } else if (act === "close-sheet") {
      closeSheet();
    } else if (act === "pick-provider") {
      S.providerId = el.getAttribute("data-id");
      closeSheet();
      render({ animate: false });
      toast("已切换到 " + provider().name);
    } else if (act === "cap-tab") {
      S.capabilityTab = el.getAttribute("data-cap");
      render({ animate: false });
    } else if (act === "kb") {
      S.kb = el.getAttribute("data-kb");
      go("kb-manage", "push");
    } else if (act === "flag") {
      var key = el.getAttribute("data-flag");
      toggleFlag(key);
    } else if (act === "send") {
      if (S.composer.generating) {
        S.composer.generating = false;
        S.screen = "chat-run";
        saveScrollAndRender();
        toast("已停止输出");
        return;
      }
      var ta = document.querySelector(".composer-field textarea");
      var text = ta ? ta.value.trim() : "";
      if (!text) return;
      S.composer.generating = true;
      S.composer.text = "";
      S.screen = "chat-generating";
      render({ animate: true });
      toast("已发送");
    } else if (act === "new-chat") {
      S.composer.generating = false;
      S.composer.text = "";
      S.composer.attach = null;
      go("chat-empty", "pop");
      toast("新对话");
    } else if (act === "open-session") {
      go("chat-run", "pop");
      toast("已载入会话");
    } else if (act === "del-session") {
      var rowEl = el.closest(".row");
      if (rowEl) {
        rowEl.classList.add("row-out");
        setTimeout(function () {
          var title = rowEl.querySelector(".row-title");
          var name = title ? title.textContent : "";
          S.history = S.history.filter(function (h) { return h[0] !== name; });
          saveScrollAndRender();
          toast("已删除会话");
        }, 220);
      }
    } else if (act === "voice-mode") {
      S.voice.mode = S.voice.mode === "stream" ? "press" : "stream";
      S.voice.hint = S.voice.mode === "stream" ? "免按压，说完稍停即发送" : "按住下方按钮说话";
      saveScrollAndRender();
    } else if (act === "toggle-upload") {
      S.composer.upload = !S.composer.upload;
      saveScrollAndRender();
    } else if (act === "attach-file") {
      S.composer.attach = "2026-Q3-产品复盘.pdf";
      S.composer.upload = false;
      saveScrollAndRender();
    } else if (act === "attach-image") {
      S.composer.attach = "截图_20261120.png";
      S.composer.upload = false;
      saveScrollAndRender();
    } else if (act === "drop-attach") {
      S.composer.attach = null;
      saveScrollAndRender();
    } else if (act === "pick-file") {
      toast("选择 PDF / DOCX / XLSX / MD / TXT");
    } else if (act === "mcp-transport") {
      S.mcp.transport = el.getAttribute("data-t");
      saveScrollAndRender();
      mountSheet("mcp-config", false);
    } else if (act === "mcp-add-header") {
      S.mcp.headers.push(["", ""]);
      mountSheet("mcp-config", false);
    } else if (act === "qq-start") {
      S.flags.qqEnabled = true;
      S.qq.status = "已就绪";
      saveScrollAndRender();
      toast("已提交启动");
    } else if (act === "qq-test") {
      toast("HTTP 通 · WS 通 · 账号在线");
    } else if (act === "qq-diagnose") {
      toast("地址可达 · 握手成功 · QQ 在线");
    } else if (act === "qq-reset") {
      S.flags.qqCircuit = false;
      saveScrollAndRender();
      toast("已恢复");
    } else if (act === "qq-enable") {
      if (!flag("qqRisk")) return;
      S.flags.qqEnabled = true;
      closeSheet();
      render({ animate: false });
      toast("已开启");
    } else if (act === "toast") {
      toast(el.getAttribute("data-msg") || "");
    }
  }

  /* 开关状态的就地更新：不重建 DOM，否则 CSS 过渡会被打断（评审第 4 条）。 */
  var FLAG_STATUS = {
    thinking: function (on) { return on ? "已开启 · 先推理再回答" : "已关闭"; },
    vision: function (on) { return on ? "已授权 · DeepSeek V3" : "未授权"; },
    visionAuto: function (on) { return on ? "图片自动转成描述后回答" : "已关闭"; },
    hidden: function (on) { return on ? "已开启 · 优先 shell 隐藏虚拟屏" : "已关闭 · 兼容模式"; },
    watch: function (on) { return on ? "已开启 · ≤2fps" : "已关闭"; },
    selfHeal: function (on) { return on ? "被系统关闭后自动重连" : "已关闭"; },
    webview: function (on) { return on ? "已开启 · 仅公网 https" : "已关闭"; },
    qqSchedule: function (on) { return on ? "已开启" : "已关闭"; },
    qqAutoReply: function (on) { return on ? "已开启 · 你发消息后 AI 以小号身份回你" : "已关闭"; },
    qqMemory: function (on) { return on ? "已开启 · 参考跨会话记忆与画像" : "已关闭"; },
    qqPacing: function (on) { return on ? "已开启 · 字/秒 + 思考时间，深夜更慢" : "已关闭 · 生成完立即发出"; },
    qqTools: function (on) { return on ? "已开启 · 联网/热榜/资料库/待办/文档/日记" : "已关闭"; },
    qqPhone: function (on) { return on ? "已开启 · 后台建副屏（较高风险）" : "已关闭 · 不建副屏、不抢前台"; },
    qqDiary: function (on) { return on ? "已开启 · 本地保存，每天最多 3 条" : "已关闭"; },
    qqWake: function (on) { return on ? "已开启" : "已关闭 · 熄屏时跳过并记录原因"; },
    qqPoisson: function (on) { return on ? "泊松 · 偶发成串" : "均匀 · 平缓随机"; },
    pet: function (on) { return on ? "已开启 · 桌面悬浮" : "已关闭"; },
    petIdle: function (on) { return on ? "没人理时偶尔动一下" : "已关闭"; },
    petBubble: function (on) { return on ? "回复时在形象旁显示文字" : "已关闭"; },
    petLowPower: function (on) { return on ? "15fps · 省电优先" : "已关闭"; },
    reduceMotion: function (on) { return on ? "已停止待机动作与呼吸" : "已关闭"; }
  };

  function applyFlag(key, on) {
    S.flags[key] = on;
    document.querySelectorAll('input[data-flag="' + key + '"]').forEach(function (i) {
      i.checked = on;
    });
    document.querySelectorAll('[data-act="flag"][data-flag="' + key + '"]').forEach(function (c) {
      c.setAttribute("aria-pressed", on ? "true" : "false");
    });
    var fn = FLAG_STATUS[key];
    if (!fn) return;
    document.querySelectorAll(".row").forEach(function (row) {
      if (!row.querySelector('input[data-flag="' + key + '"]')) return;
      var st = row.querySelector(".row-status");
      if (st) st.textContent = fn(on);
    });
  }

  function toggleFlag(key) {
    var isQQ = key === "qqEnabled";
    if (isQQ && !S.flags.qqEnabled && !flag("qqRisk")) {
      mountSheet("qq-risk", false);
      return;
    }
    var next = !flag(key);
    if (isQQ) {
      S.flags.qqEnabled = next;
      if (!next) S.flags.qqAutoReply = false;
      saveScrollAndRender();
      return;
    }
    applyFlag(key, next);
  }

  function onChange(e) {
    var t = e.target;
    if (!t.matches || !t.matches("input[data-flag]")) return;
    var key = t.getAttribute("data-flag");
    if (key === "qqRisk") {
      S.flags.qqRisk = t.checked;
      var host = document.getElementById("sheet-host");
      if (host) {
        var btn = host.querySelector('[data-act="qq-enable"]');
        if (btn) btn.disabled = !t.checked;
      }
      return;
    }
    if (key === "qqEnabled" && t.checked && !flag("qqRisk")) {
      t.checked = false;
      mountSheet("qq-risk", false);
      return;
    }
    if (key === "qqEnabled") {
      S.flags.qqEnabled = t.checked;
      if (!t.checked) S.flags.qqAutoReply = false;
      saveScrollAndRender();
      return;
    }
    applyFlag(key, t.checked);
  }

  function onInput(e) {
    var t = e.target;
    if (t.tagName !== "TEXTAREA") return;
    var field = t.closest(".composer-field");
    if (field) field.classList.toggle("has-text", t.value.length > 0);
    var send = document.querySelector(".send-btn");
    if (send && !S.composer.generating) {
      send.setAttribute("data-ready", t.value.trim() ? "true" : "false");
    }
    t.style.height = "36px";
    t.style.height = Math.min(t.scrollHeight, 102) + "px";
    S.composer.text = t.value;
  }

  function boot() {
    var params = new URLSearchParams(location.search);
    var theme = params.get("theme") || "warm";
    document.documentElement.setAttribute("data-theme", theme);
    var screen = params.get("screen");
    if (screen && SCREENS[screen]) S.screen = screen;
    if (params.get("cap")) S.capabilityTab = params.get("cap");
    if (params.get("kb")) S.kb = params.get("kb");
    if (params.get("qq") === "on") {
      S.flags.qqEnabled = true;
      S.flags.qqRisk = true;
    }
    if (params.get("generating") === "1") S.composer.generating = true;
    if (params.get("phase")) S.voice.phase = params.get("phase");
    if (params.get("voice") === "press") {
      S.voice.mode = "press";
      S.voice.hint = "按住下方按钮说话";
    }
    render({ animate: true });
    document.addEventListener("click", onClick);
    document.addEventListener("change", onChange);
    document.addEventListener("input", onInput);
    var sheet = params.get("sheet");
    if (sheet && SHEETS[sheet]) {
      setTimeout(function () { mountSheet(sheet, false); }, 60);
    }
    window.addEventListener("resize", positionUnderline);
  }

  window.PrismUI = { boot: boot, state: S, go: go, render: render };
})();

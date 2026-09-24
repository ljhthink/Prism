/*
 * Prism 桌宠渲染器（批次24 / ADR-051 D2-D3）
 * ---------------------------------------------------------------------------
 * 运行在 Android WebView 内，向 Kotlin 侧暴露 window.PetRenderer。
 *
 * 设计要点（为什么是现在这个样子）：
 *
 * 1) **动作目录由形象包提供，本文件只负责执行**。别名（happy / wave / ...）到
 *    模型内 motion / expression 的映射写在 pet.json 里，由 Kotlin 解析后经 load()
 *    下发。这样同一套渲染器可以驱动任意用户上传的形象。
 *
 * 2) **motion 失败必须回退到 expression**。昔涟模型 23 条 motion 里有 14 条只有
 *    Expression 而没有 File（Live2DViewerEX 的非标准写法），标准运行时会把它当成
 *    "没有文件的 motion"而静默失败。上游 interaction.ts 的对策正是这层回退；
 *    漏掉它，用户会感觉"点了大半地方没反应"。
 *
 * 3) **眨眼 / 呼吸 / 口型 / 视线全部自建**。官方 CubismModelSettingJson 只读
 *    HitAreas[].Name/Id 与 Motions[].File 等标准字段，完全不读 Controllers
 *    （EyeBlink / AutoBreath / LipSync / MouseTracking 都在那里）。昔涟模型的
 *    Groups 为空，因此 pixi-live2d-display 也拿不到眨眼组——必须自己驱动参数。
 *
 * 4) **参数覆写在 beforeModelUpdate 时机注入**。pixi-live2d-display 的
 *    Cubism4InternalModel 在 update() 中的顺序是：
 *      motionManager.update() → emit("afterMotionUpdate") → physics → pose
 *      → emit("beforeModelUpdate") → coreModel.update()
 *    在 beforeModelUpdate 里写参数，才能既覆盖动作曲线、又被后续 update 正确求值。
 *
 * 5) **所有对 Kotlin 的回报都是 JSON 字符串**，且**不含文件绝对路径与用户正文**
 *    （rules/07 §19.3 脱敏要求）。
 */
(function () {
  'use strict';

  var EYE_L = 'ParamEyeLOpen';
  var EYE_R = 'ParamEyeROpen';
  var MOUTH = 'ParamMouthOpenY';
  var BREATH = 'ParamBreath';
  var ANGLE_X = 'ParamAngleX';
  var ANGLE_Y = 'ParamAngleY';
  var EYE_BALL_X = 'ParamEyeBallX';
  var EYE_BALL_Y = 'ParamEyeBallY';

  // 眨眼节奏（毫秒）：与上游 Cubism 默认观感对齐（2~6s 一次）
  var BLINK_MIN_MS = 2000;
  var BLINK_MAX_MS = 6000;
  var BLINK_CLOSE_MS = 90;
  var BLINK_OPEN_MS = 140;

  // 口型节奏：上游 mouth-sync.ts 的同类取值（0.15~0.85，约 180ms 一次跳变）
  var MOUTH_MIN = 0.15;
  var MOUTH_MAX = 0.85;
  var MOUTH_TICK_MS = 180;
  var MOUTH_STOP_FADE_MS = 200;

  // 动作优先级：**必须用 FORCE(3)**，不能用库默认的 NORMAL(2)。
  // 依据（`pixi-live2d-display` 的 `MotionManager.state.reserve()` 反编译核实）：
  //     if (priority < 3) { if (priority <= currentPriority) return false; ... }
  // 优先级的"释放"只发生在动作 **complete()** 时，而昔涟模型的全部 motion 都是
  // `Loop=true`（Tick3 时长 60.3s、动作#6 为 0.5~4s，但循环）⇒ 第一条动作永不结束
  // ⇒ `currentPriority` 长期停在 2 ⇒ **此后所有 NORMAL 动作被永久拒绝**。
  // 真机表现：第一条待机动作播完后，桌宠只剩表情变化，点命中区/助手点播都不会再换动作。
  // 真机日志（修复前）：`alias=wave kind=motion` 之后每次都是
  // `fell back ... reason=motion 未启动`。
  // FORCE 会跳过整个 `priority < 3` 分支，因此每次都能抢占。副作用是待机动作可以打断
  // 上一条动作——在本模型"动作即循环"的语义下这正是想要的效果（否则永远定格在第一条）。
  var MOTION_PRIORITY_FORCE = 3;

  var state = {
    app: null,
    model: null,
    sprite: null,
    kind: null,
    ready: false,
    paused: false,
    reduceMotion: false,
    lowPower: false,
    descriptor: null,
    // 覆盖层参数
    blink: { at: 0, phase: 'open', value: 1 },
    mouth: { active: false, until: 0, nextAt: 0, value: 0, fadingFrom: 0, fadeUntil: 0 },
    focus: { x: 0, y: 0, tx: 0, ty: 0 },
    breath: { t: 0 },
    expression: { name: null, resetAt: 0 },
    // 图像后端
    frames: [],
    frameIndex: 0,
    frameAt: 0,
    tween: null,
    tweenT: 0,
    /**
     * 加载世代 —— 防止"在途加载"把已被替换的形象塞回舞台。
     *
     * **修的是真实缺陷**：用户快速切换形象、或改设置触发重载时，上一轮
     * `Live2DModel.from(...)` / `Assets.load(...)` 的 promise 可能在新一轮
     * `clearScene()` 之后才 settle。没有世代校验时，旧对象会被 addChild 回去 ——
     * 结果是**两个模型同时渲染**（幽灵重叠），且旧模型永不销毁（WebGL 纹理泄漏），
     * 在只有 9MB 纹理的设备上会很快把内存吃满。
     * 每次 clearScene 自增；promise settle 后代次不一致即自行销毁并放弃。
     */
    loadEpoch: 0
  };

  // ---------------------------------------------------------------- 桥接

  function hostPost(payload) {
    try {
      if (window.PetHost && typeof window.PetHost.postMessage === 'function') {
        window.PetHost.postMessage(JSON.stringify(payload));
      }
    } catch (e) {
      /* 桥不可用时静默：渲染不能因为回报失败而中断 */
    }
  }

  function ok(extra) {
    var out = { ok: true };
    if (extra) { for (var k in extra) { if (extra.hasOwnProperty(k)) out[k] = extra[k]; } }
    return JSON.stringify(out);
  }

  function fail(reason) {
    return JSON.stringify({ ok: false, reason: String(reason || 'unknown') });
  }

  /** 只回报可读且不含路径的错误串。 */
  function describeError(e) {
    var msg = (e && e.message) ? String(e.message) : String(e || 'error');
    return msg.length > 200 ? msg.slice(0, 200) : msg;
  }

  function showFallback(text) {
    var el = document.getElementById('fallback');
    if (el) {
      el.textContent = text;
      el.style.display = 'flex';
    }
  }

  // ---------------------------------------------------------------- 初始化

  function ensureApp() {
    if (state.app) return true;
    if (typeof PIXI === 'undefined') {
      hostPost({ type: 'error', code: 'pixi_missing', message: 'PIXI 未加载' });
      showFallback('渲染组件未加载');
      return false;
    }
    var canvas = document.getElementById('stage');
    state.app = new PIXI.Application({
      view: canvas,
      backgroundAlpha: 0,
      antialias: true,
      autoDensity: true,
      resolution: Math.min(window.devicePixelRatio || 1, 2),
      resizeTo: window,
      powerPreference: 'low-power'
    });
    state.app.ticker.add(onTick);
    return true;
  }

  function coreModel() {
    return (state.model && state.model.internalModel && state.model.internalModel.coreModel) || null;
  }

  function setParam(id, value) {
    var cm = coreModel();
    if (!cm || typeof cm.setParameterValueById !== 'function') return;
    if (typeof cm.getParameterIndex === 'function' && cm.getParameterIndex(id) < 0) return;
    cm.setParameterValueById(id, value);
  }

  // ---------------------------------------------------------------- 加载

  function load(descriptorJson) {
    var d;
    try {
      d = JSON.parse(descriptorJson);
    } catch (e) {
      return fail('形象描述不是合法 JSON');
    }
    state.descriptor = d;
    state.kind = d.kind;
    state.reduceMotion = !!d.reduceMotion;
    state.lowPower = !!d.lowPower;
    if (!ensureApp()) return fail('渲染组件未加载');

    clearScene();

    if (d.kind === 'live2d') {
      loadLive2D(d);
    } else if (d.kind === 'image') {
      loadImage(d);
    } else {
      hostPost({ type: 'error', code: 'bad_kind', message: '未知形象类型' });
      showFallback('形象类型不支持');
      return fail('未知形象类型');
    }
    return ok({ kind: d.kind });
  }

  function clearScene() {
    // 世代自增：令所有"在途的加载 promise"失效（见 loadEpoch 说明）
    state.loadEpoch += 1;
    state.ready = false;
    state.tween = null;
    if (state.model) {
      try { state.app.stage.removeChild(state.model); state.model.destroy(); } catch (e) { /* 已销毁 */ }
      state.model = null;
    }
    if (state.sprite) {
      try { state.app.stage.removeChild(state.sprite); state.sprite.destroy(); } catch (e) { /* 已销毁 */ }
      state.sprite = null;
    }
    state.frames = [];
    state.expression.name = null;
    state.expression.resetAt = 0;
    state.mouth.active = false;
    state.mouth.value = 0;
    // 眨眼相位也要复位：否则换形象后可能长期停在"闭眼/半睁"的相位上
    state.blink.at = 0;
    state.blink.phase = 'open';
    state.blink.value = 1;
  }

  function loadLive2D(d) {
    if (typeof PIXI.live2d === 'undefined' || typeof PIXI.live2d.Live2DModel === 'undefined') {
      hostPost({ type: 'error', code: 'runtime_missing', message: 'Live2D 运行时未加载' });
      showFallback('Live2D 组件未加载');
      return;
    }
    if (!d.modelUrl) {
      hostPost({ type: 'error', code: 'no_model', message: '形象未声明模型文件' });
      showFallback('形象缺少模型文件');
      return;
    }

    var epoch = state.loadEpoch;
    PIXI.live2d.Live2DModel.from(d.modelUrl, {
      // 命中测试与视线跟随由我们自己驱动（触摸事件在 Kotlin 侧统一分发），
      // 关掉自动交互可避免 WebView 内部事件与悬浮窗拖动手势互相干扰。
      autoHitTest: false,
      autoFocus: false,
      autoUpdate: true
    }).then(function (model) {
      if (epoch !== state.loadEpoch) {
        // 已被新一轮加载取代：立即释放，不挂到舞台上
        try { model.destroy(); } catch (e) { /* 已销毁 */ }
        return;
      }
      state.model = model;
      state.app.stage.addChild(model);
      fitLive2D();
      installParameterOverrides(model);

      // 初始化动作：昔涟模型用 Start 组做过一次"开/回正"，缺省表情也在这里应用
      if (d.defaultExpression) applyExpression(d.defaultExpression, false);

      var version = '';
      var coreMocSupport = '';
      try {
        if (window.Live2DCubismCore && Live2DCubismCore.Version &&
            typeof Live2DCubismCore.Version.csmGetVersion === 'function') {
          var v = Live2DCubismCore.Version.csmGetVersion();
          version = [ (v >> 24) & 0xFF, (v >> 16) & 0xFF, v & 0xFFFF ].join('.');
        }
      } catch (e) { version = ''; }
      try {
        if (window.Live2DCubismCore && Live2DCubismCore.Version &&
            typeof Live2DCubismCore.Version.csmGetLatestMocVersion === 'function') {
          coreMocSupport = String(Live2DCubismCore.Version.csmGetLatestMocVersion());
        }
      } catch (e) { coreMocSupport = ''; }

      // ⚠️ 注意区分两个**不同**的版本号：
      //   coreMocSupport = 这套 Core **最高能接受**的 moc 版本
      //   mocVersion     = **这个模型自己**的 moc 版本
      // 只有两者相减才有判据意义（mocVersion > coreMocSupport ⇒ 必然加载失败）。
      // 早期实现把 coreMocSupport 当成 mocVersion 记进 `moc=`，等于用"Core 支持到 5"
      // 冒充"昔涟这个模型是 5"—— 真机上最关键的那一问仍然答不了。
      // 模型自身的版本需要拿到 moc 字节再调 csmGetMocVersion(moc, size)。
      probeModelMocVersion(d.modelUrl);

      state.ready = true;
      hostPost({
        type: 'ready',
        kind: 'live2d',
        coreVersion: version,
        coreMocSupport: coreMocSupport,
        width: Math.round(model.width || 0),
        height: Math.round(model.height || 0),
        hitAreas: collectHitAreas(model)
      });
    }).catch(function (e) {
      // **世代校验同样必须出现在失败路径**（guardrail R3-B 的 N-2）：
      // 快速连续切换形象时，被取代的那次加载若失败，会无条件执行
      // `showFallback` + 上报 `model_load_failed` —— 结果是
      // **把已经渲染成功的当前模型拆掉，并报一个假故障**（"形象不受支持"）。
      // 只有"这一次加载仍然是当前世代"时，失败才代表真的失败。
      if (epoch !== state.loadEpoch) return;
      hostPost({ type: 'error', code: 'model_load_failed', message: describeError(e) });
      showFallback('形象加载失败');
    });
  }

  function loadImage(d) {
    var urls = d.imageUrls || [];
    if (!urls.length) {
      hostPost({ type: 'error', code: 'no_image', message: '形象未声明图片' });
      showFallback('形象缺少图片');
      return;
    }
    var tasks = urls.map(function (u) { return PIXI.Assets.load(u); });
    var epoch = state.loadEpoch;
    Promise.all(tasks).then(function (textures) {
      if (epoch !== state.loadEpoch) {
        textures.forEach(function (t) { try { t.destroy(true); } catch (e) { /* 已销毁 */ } });
        return;
      }
      state.frames = textures;
      state.frameIndex = 0;
      state.frameAt = 0;
      var sprite = new PIXI.Sprite(textures[0]);
      sprite.anchor.set(0.5, 0.5);
      state.sprite = sprite;
      state.app.stage.addChild(sprite);
      fitImage();
      state.ready = true;
      hostPost({
        type: 'ready',
        kind: 'image',
        width: Math.round(textures[0].width || 0),
        height: Math.round(textures[0].height || 0),
        hitAreas: []
      });
    }).catch(function (e) {
      if (epoch !== state.loadEpoch) return; // 同 live2d 分支：过期失败不得拆掉当前形象
      hostPost({ type: 'error', code: 'image_load_failed', message: describeError(e) });
      showFallback('图片加载失败');
    });
  }

  function collectHitAreas(model) {
    try {
      var areas = model.internalModel && model.internalModel.hitAreas;
      return areas ? Object.keys(areas) : [];
    } catch (e) {
      return [];
    }
  }

  /**
   * 读取**模型自身**的 moc3 版本并单独回报（`type: 'diag'`）。
   *
   * 为什么需要额外取一次数据：模型的 `.moc3` 是 pixi-live2d-display 内部加载的，
   * 不把字节交出来；而判定"这套 Core 能不能吃这个模型"恰恰需要那个字节数组。
   * 这里重新 `fetch` 一次模型主清单 → 取出 `FileReferences.Moc` → 再 `fetch` moc。
   * **第二次请求会命中 WebView 的 HTTP 缓存**（同一 URL、刚被加载过），所以开销可忽略；
   * 任何一步失败都静默放弃（诊断信息缺失不应影响渲染）。
   */
  function probeModelMocVersion(modelUrl) {
    if (!window.Live2DCubismCore || !Live2DCubismCore.Version ||
        typeof Live2DCubismCore.Version.csmGetMocVersion !== 'function') {
      return;
    }
    fetch(modelUrl).then(function (r) { return r.json(); }).then(function (settings) {
      var moc = settings && settings.FileReferences && settings.FileReferences.Moc;
      if (!moc) return null;
      var base = modelUrl.substring(0, modelUrl.lastIndexOf('/') + 1);
      return fetch(base + moc.split('/').map(encodeURIComponent).join('/')).then(function (r) {
        return r.arrayBuffer();
      });
    }).then(function (buf) {
      if (!buf) return;
      // ⚠️ 必须传 **Moc 实例**而不是裸 ArrayBuffer：Core 的实现是
      //   `csmGetMocVersion = (moc, mocBytes) => _csm.getMocVersion(moc._ptr, mocBytes.byteLength)`
      // 传 ArrayBuffer 时 `moc._ptr` 为 undefined ⇒ wasm 收到 0 ⇒ 恒返回
      // `MocVersion_Unknown(0)`。真机实测正是如此（`pet model moc version=0`），
      // 等于这个诊断字段白加了。现在取到的才是"这个模型自己是第几版"。
      var mocObj = Live2DCubismCore.Moc.fromArrayBuffer(buf);
      if (!mocObj) return;
      try {
        var v = Live2DCubismCore.Version.csmGetMocVersion(mocObj, buf);
        hostPost({ type: 'diag', mocVersion: String(v) });
      } finally {
        // Moc 会在 wasm 堆上分配内存，用完必须释放（否则每次开窗泄漏一份 moc）
        try { mocObj._release(); } catch (e) { /* 旧版 Core 无此方法 */ }
      }
    }).catch(function () {
      /* 诊断失败不影响渲染 */
    });
  }

  function fitLive2D() {
    var model = state.model;
    if (!model || !state.app) return;
    var w = state.app.renderer.screen.width;
    var h = state.app.renderer.screen.height;
    var mw = model.width || 1;
    var mh = model.height || 1;
    var extra = (state.descriptor && state.descriptor.scale) || 1;
    var s = Math.min(w / mw, h / mh) * extra;
    model.anchor.set(0.5, 0.5);
    model.scale.set(s);
    model.position.set(w / 2, h / 2);
  }

  function fitImage() {
    var sprite = state.sprite;
    if (!sprite || !state.app) return;
    var w = state.app.renderer.screen.width;
    var h = state.app.renderer.screen.height;
    var extra = (state.descriptor && state.descriptor.scale) || 1;
    var s = Math.min(w / sprite.texture.width, h / sprite.texture.height) * extra;
    sprite.scale.set(s);
    sprite.position.set(w / 2, h / 2);
  }

  function onResize() {
    if (!state.app) return;
    if (state.kind === 'live2d') fitLive2D(); else fitImage();
  }

  // ---------------------------------------------------------------- 参数覆写

  function installParameterOverrides(model) {
    var im = model.internalModel;
    if (!im) return;
    im.on('beforeModelUpdate', function () {
      applyEyeBlink();
      applyMouth();
      applyFocus();
      applyBreath();
    });
  }

  function applyEyeBlink() {
    // 眨眼**不**受「减少动效」影响：PRD US-2406 AC-4 明确要求减少动效时"仅保留眨眼与
    // 用户触发的动作"。完全不眨眼会让角色看起来像一张静止贴图，反而更差。
    var now = nowMs();
    if (!state.blink.at) state.blink.at = now + rand(BLINK_MIN_MS, BLINK_MAX_MS);
    var elapsed = now - state.blink.at;
    var value = 1;
    if (elapsed >= 0 && state.blink.phase === 'open') {
      state.blink.phase = 'close';
      state.blink.at = now;
    }
    if (state.blink.phase === 'close') {
      var t = (now - state.blink.at) / BLINK_CLOSE_MS;
      if (t >= 1) { t = 1; state.blink.phase = 'hold'; state.blink.at = now; }
      value = 1 - t;
    } else if (state.blink.phase === 'hold') {
      value = 0;
      if (now - state.blink.at >= 30) { state.blink.phase = 'reopen'; state.blink.at = now; }
    } else if (state.blink.phase === 'reopen') {
      var r = (now - state.blink.at) / BLINK_OPEN_MS;
      if (r >= 1) {
        r = 1;
        state.blink.phase = 'open';
        state.blink.at = now + rand(BLINK_MIN_MS, BLINK_MAX_MS);
      }
      value = r;
    }
    setParam(EYE_L, value);
    setParam(EYE_R, value);
  }

  function applyMouth() {
    var m = state.mouth;
    var now = nowMs();
    if (m.active) {
      if (now >= m.until) {
        // 播报窗口结束：从当前值淡出到 0，避免"突然闭不上嘴"
        m.active = false;
        m.fadingFrom = m.value;
        m.fadeUntil = now + MOUTH_STOP_FADE_MS;
      } else if (now >= m.nextAt) {
        m.value = MOUTH_MIN + Math.random() * (MOUTH_MAX - MOUTH_MIN);
        m.nextAt = now + MOUTH_TICK_MS;
      }
    } else if (m.fadeUntil > now) {
      var t = 1 - (m.fadeUntil - now) / MOUTH_STOP_FADE_MS;
      m.value = m.fadingFrom * (1 - Math.max(0, Math.min(1, t)));
    } else {
      m.value = 0;
    }
    setParam(MOUTH, m.value);
  }

  function applyFocus() {
    var f = state.focus;
    f.x += (f.tx - f.x) * 0.12;
    f.y += (f.ty - f.y) * 0.12;
    setParam(ANGLE_X, f.x * 30);
    setParam(ANGLE_Y, f.y * 30);
    setParam(EYE_BALL_X, f.x);
    setParam(EYE_BALL_Y, f.y);
  }

  function applyBreath() {
    if (state.reduceMotion) { setParam(BREATH, 0); return; }
    var period = state.lowPower ? 5000 : 3600;
    state.breath.t += 1 / (period / 16.7);
    if (state.breath.t > 1) state.breath.t -= 1;
    setParam(BREATH, (Math.sin(state.breath.t * Math.PI * 2) + 1) / 2);
  }

  // ---------------------------------------------------------------- 动作

  function resolveAlias(alias) {
    var d = state.descriptor;
    if (!d || !d.actions) return null;
    var key = String(alias || '').trim().toLowerCase();
    if (!key) return null;
    var ref = d.actions[key];
    if (!ref || !ref.target) return null;
    return ref;
  }

  /**
   * 播放动作。返回**同步** JSON 结果（evaluateJavascript 只能拿同步返回值），
   * 实际的异步播放结果通过 hit/action 事件另行回报。
   */
  function play(alias) {
    if (!state.ready) return fail('形象尚未加载完成');
    if (state.kind === 'image') return playImage(alias);
    var ref = resolveAlias(alias);
    if (!ref) return fail('该形象没有别名 ' + alias);
    return playLive2D(ref, alias);
  }

  function playLive2D(ref, alias) {
    var model = state.model;
    if (!model) return fail('模型未加载');
    cancelExpressionReset();

    if (ref.kind === 'expression') {
      var done = applyExpression(ref.target, true);
      if (done) hostPost({ type: 'action', alias: alias, kind: 'expression', target: ref.target });
      return done ? ok({ alias: alias, kind: 'expression', target: ref.target })
                  : fail('表情不存在：' + ref.target);
    }

    // motion：失败（组/下标不存在、或该 motion 没有文件）→ 回退 expression
    var group = ref.target;
    var index = typeof ref.index === 'number' ? ref.index : 0;
    var motionPromise = null;
    try {
      if (index < 0) {
        // 负下标 = **组内随机**（`model.motion(group, undefined, priority)` 走
        // `startRandomMotion`；注意第 2 参必须是 `undefined`，否则会被当成下标）。
        // 昔涟包的 `idle` 用它表达"待机时随机挑一个待机动作"；若直接把 -1 传下去，
        // 运行时会取到 undefined 定义并静默失败——表现为"开了待机却什么都不做"。
        // 必须显式传优先级：`motion(group)` 会让优先级落到库默认的 NORMAL，见
        // `MOTION_PRIORITY_FORCE` 的说明。
        motionPromise = model.motion(group, undefined, MOTION_PRIORITY_FORCE);
      } else {
        motionPromise = model.motion(group, index, MOTION_PRIORITY_FORCE);
      }
    } catch (e) {
      motionPromise = null;
    }
    if (!motionPromise || typeof motionPromise.then !== 'function') {
      return fallbackToExpression(ref, alias, 'motion 组不可用');
    }
    motionPromise.then(function (started) {
      if (started === false) {
        // pixi-live2d-display 的 `state.reserve()` 在「同一条动作正在播放/已被占用」
        // 时也返回 false（与「动作不存在」同码），故措辞须覆盖两种成因，
        // 否则真机日志会把它误读成"包配错了"。
        fallbackToExpression(ref, alias, 'motion 未启动（不存在或已在播放）');
      } else {
        hostPost({ type: 'action', alias: alias, kind: 'motion', target: group, index: index });
      }
    }).catch(function (e) {
      fallbackToExpression(ref, alias, describeError(e));
    });
    return ok({ alias: alias, kind: 'motion', target: group, index: index, pending: true });
  }

  function fallbackToExpression(ref, alias, why) {
    var expr = ref.fallbackExpression || ref.target;
    if (applyExpression(expr, true)) {
      hostPost({
        type: 'action', alias: alias, kind: 'expression',
        target: expr, fallback: true, reason: why
      });
    } else {
      hostPost({ type: 'action', alias: alias, kind: 'none', fallback: true, reason: why });
    }
  }

  function applyExpression(name, armReset) {
    var model = state.model;
    if (!model || !name) return false;
    var em = model.internalModel && model.internalModel.motionManager &&
      model.internalModel.motionManager.expressionManager;
    if (!em) return false;
    try {
      em.setExpression(name);
    } catch (e) {
      return false;
    }
    state.expression.name = name;
    if (armReset) {
      var secs = (state.descriptor && state.descriptor.expressionResetSeconds);
      if (typeof secs === 'number' && secs > 0) {
        state.expression.resetAt = nowMs() + secs * 1000;
      } else {
        state.expression.resetAt = 0;
      }
    }
    return true;
  }

  function cancelExpressionReset() {
    state.expression.resetAt = 0;
  }

  /** 显式设置表情（Kotlin 侧主动调用，例如按心情切换）。 */
  function setExpression(name) {
    if (!state.ready) return fail('形象尚未加载完成');
    if (state.kind !== 'live2d') return fail('当前形象类型不支持表情');
    cancelExpressionReset();
    return applyExpression(name, true)
      ? ok({ expression: name })
      : fail('表情不存在：' + name);
  }

  // ---------------------------------------------------------------- 图像后端动作

  function playImage(alias) {
    var key = String(alias || '').trim().toLowerCase();
    if (state.kind !== 'image') return fail('当前形象不是图片类型');
    if (!state.sprite) return fail('图片未加载');
    if (key === 'reset') { state.tween = null; resetSpriteTransform(); return ok({ alias: key }); }
    var table = {
      happy: { dy: -12, scale: 1.08, rot: 0 },
      sad: { dy: 10, scale: 0.96, rot: 0 },
      angry: { shake: 10 },
      surprised: { scale: 1.14 },
      shy: { rot: 6, scale: 0.97 },
      think: { rot: -8 },
      wink: { rot: 5 },
      wave: { sway: 14 },
      tap: { squash: 0.12 },
      idle: { dy: -4, scale: 1.03 }
    };
    var spec = table[key] || table.idle;
    state.tween = spec;
    state.tweenT = 0;
    return ok({ alias: key, kind: 'image' });
  }

  function resetSpriteTransform() {
    if (!state.sprite) return;
    fitImage();
    state.sprite.rotation = 0;
  }

  function applyImageTween(dtMs) {
    if (!state.tween || !state.sprite) return;
    state.tweenT += dtMs / 1000;
    var spec = state.tween;
    var t = state.tweenT;
    var base = state.sprite;
    var s = Math.min(state.app.renderer.screen.width / base.texture.width,
                     state.app.renderer.screen.height / base.texture.height) *
            ((state.descriptor && state.descriptor.scale) || 1);
    var ease = Math.exp(-2.2 * t) * Math.cos(t * Math.PI * 1.6);
    var dx = 0, dy = 0, sc = 1, rot = 0;
    if (spec.shake) { dx = Math.sin(t * Math.PI * 12) * spec.shake * Math.exp(-3 * t); }
    if (spec.sway) { rot = Math.sin(t * Math.PI * 4) * spec.sway * Math.exp(-1.6 * t); }
    if (spec.dy) { dy = spec.dy * ease; }
    if (spec.scale) { sc = 1 + (spec.scale - 1) * ease; }
    if (spec.rot) { rot = spec.rot * ease; }
    if (spec.squash) { sc = 1 + spec.squash * Math.sin(t * Math.PI * 8) * Math.exp(-3 * t); }
    base.position.set(state.app.renderer.screen.width / 2 + dx,
                      state.app.renderer.screen.height / 2 + dy);
    base.scale.set(s * sc);
    base.rotation = rot;
    if (t > 2.2) { state.tween = null; resetSpriteTransform(); }
  }

  // ---------------------------------------------------------------- 交互

  /**
   * 点击命中测试。坐标是相对画布的归一化值（0..1），由 Kotlin 侧从悬浮窗触摸事件换算，
   * 这样手势判定（拖动 vs 点击）留在 Kotlin（可单测），命中判定留在渲染层（需要模型变换）。
   */
  function tapAt(nx, ny) {
    if (!state.ready) return fail('形象尚未加载完成');
    if (state.kind !== 'live2d') {
      var alias = (state.descriptor && state.descriptor.tapAlias) || 'tap';
      playImage(alias);
      return ok({ hit: true, areas: [], alias: alias });
    }
    var model = state.model;
    if (!model) return fail('模型未加载');
    var w = state.app.renderer.screen.width;
    var h = state.app.renderer.screen.height;
    var areas = [];
    try {
      areas = model.hitTest(nx * w, ny * h) || [];
    } catch (e) {
      areas = [];
    }
    setFocus(nx * 2 - 1, ny * 2 - 1);
    var hitMap = (state.descriptor && state.descriptor.hitActions) || {};
    var alias2 = null;
    for (var i = 0; i < areas.length; i++) {
      if (hitMap[areas[i]]) { alias2 = hitMap[areas[i]]; break; }
    }
    if (!alias2) alias2 = (state.descriptor && state.descriptor.tapAlias) || null;
    // Tap hit-test is driven by the host (autoHitTest is disabled), so this is the only
    // place a 'hit' event can originate; report it to give the host a durable trace.
    hostPost({ type: 'hit', areas: areas, alias: alias2 || '' });
    if (alias2) play(alias2);
    return ok({ hit: areas.length > 0, areas: areas, alias: alias2 || '' });
  }

  function setFocus(x, y) {
    state.focus.tx = Math.max(-1, Math.min(1, x));
    state.focus.ty = Math.max(-1, Math.min(1, y));
  }

  // ---------------------------------------------------------------- 口型 / 开关

  function setMouth(active, durationMs) {
    var m = state.mouth;
    var now = nowMs();
    if (active) {
      m.active = true;
      m.until = now + Math.max(120, durationMs || 0);
      m.nextAt = 0;
    } else {
      m.active = false;
      m.until = 0;
      m.fadingFrom = m.value;
      m.fadeUntil = now + MOUTH_STOP_FADE_MS;
    }
    return ok({ mouth: !!active });
  }

  function setOptions(json) {
    var o;
    try { o = JSON.parse(json); } catch (e) { return fail('选项不是合法 JSON'); }
    if (typeof o.reduceMotion === 'boolean') state.reduceMotion = o.reduceMotion;
    if (typeof o.lowPower === 'boolean') {
      state.lowPower = o.lowPower;
      if (state.app) state.app.ticker.maxFPS = o.lowPower ? 15 : 0;
    }
    if (typeof o.scale === 'number' && state.descriptor) {
      state.descriptor.scale = o.scale;
      onResize();
    }
    return ok({});
  }

  function setPaused(paused) {
    state.paused = !!paused;
    if (state.app) {
      if (state.paused) state.app.ticker.stop(); else state.app.ticker.start();
    }
    return ok({ paused: state.paused });
  }

  function currentState() {
    return JSON.stringify({
      ok: true,
      ready: state.ready,
      kind: state.kind,
      paused: state.paused,
      expression: state.expression.name,
      mouthActive: state.mouth.active,
      reduceMotion: state.reduceMotion,
      lowPower: state.lowPower
    });
  }

  function dispose() {
    clearScene();
    if (state.app) {
      try { state.app.destroy(true, { children: true }); } catch (e) { /* 已销毁 */ }
      state.app = null;
    }
    return ok({});
  }

  // ---------------------------------------------------------------- 主循环

  function onTick() {
    if (state.paused || !state.app) return;
    var dtMs = (state.app.ticker && state.app.ticker.deltaMS) || 16.7;
    var now = nowMs();

    if (state.kind === 'image') {
      if (state.frames.length > 1 && state.descriptor && state.descriptor.frameDurationMs > 0) {
        if (now - state.frameAt >= state.descriptor.frameDurationMs) {
          state.frameAt = now;
          state.frameIndex = (state.frameIndex + 1) % state.frames.length;
          if (state.sprite) state.sprite.texture = state.frames[state.frameIndex];
        }
      }
      applyImageTween(dtMs);
    }

    // 表情自动回正（PRD US-2405 AC-3）
    if (state.expression.resetAt && now >= state.expression.resetAt) {
      state.expression.resetAt = 0;
      var back = (state.descriptor && state.descriptor.defaultExpression) || null;
      if (back) applyExpression(back, false);
      else {
        var em = state.model && state.model.internalModel && state.model.internalModel.motionManager &&
          state.model.internalModel.motionManager.expressionManager;
        try { if (em) em.resetExpression(); } catch (e) { /* 无表情管理器 */ }
      }
      state.expression.name = back;
    }
  }

  // ---------------------------------------------------------------- 工具

  function nowMs() {
    return (window.performance && performance.now) ? performance.now() : Date.now();
  }

  function rand(min, max) {
    return min + Math.random() * (max - min);
  }

  window.addEventListener('resize', onResize);

  window.PetRenderer = {
    load: load,
    play: play,
    setExpression: setExpression,
    setMouth: setMouth,
    tapAt: tapAt,
    setFocus: setFocus,
    setOptions: setOptions,
    setPaused: setPaused,
    state: currentState,
    dispose: dispose
  };

  hostPost({ type: 'boot', ok: true });
})();

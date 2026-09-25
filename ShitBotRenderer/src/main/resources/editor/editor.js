"use strict";

const $ = selector => document.querySelector(selector);
const $$ = selector => Array.from(document.querySelectorAll(selector));
const clone = value => JSON.parse(JSON.stringify(value));
const query = encodeURIComponent;
const state = {
  id: null, bundle: null, sources: null, templates: [], selection: [], tab: "visual",
  saved: null, sourceDirty: false, jsonDirty: false, history: [], future: [],
  busy: false, previewUrl: null, zoom: 1, fit: true, snap: true, space: false, gesture: null
};
const layerTypes = {
  text: ["文字", "T"], image: ["图片", "▧"], avatar: ["头像", "◉"],
  rectangle: ["矩形", "▭"], circle: ["圆形", "○"], line: ["线条", "╱"],
  progress: ["进度条", "▰"], group: ["分组", "▣"], stack: ["堆叠布局", "☰"],
  grid: ["网格布局", "⊞"], condition: ["条件", "◇"], loop: ["循环", "↻"]
};
const numericFields = new Set([
  "x", "y", "width", "height", "x2", "y2", "diameter", "stroke-width", "radius", "opacity",
  "font-size", "line-height", "maximum-lines", "maximum-items", "gap", "columns", "column-gap",
  "row-gap", "cell-width", "cell-height", "item-offset-x", "item-offset-y", "value", "maximum"
]);
const fieldLabels = {
  name: "图层名称", x: "X 位置", y: "Y 位置", width: "宽度 W", height: "高度 H",
  x2: "终点 X", y2: "终点 Y", diameter: "直径", text: "文字内容", source: "图片地址",
  fill: "填充颜色", color: "颜色", background: "背景颜色", "stroke-color": "描边颜色",
  "stroke-width": "描边宽度", radius: "圆角", opacity: "不透明度 0–1",
  "font-family": "字体", "font-size": "字号", "font-style": "字形", align: "文字对齐",
  "line-height": "行高", "maximum-lines": "最多行数", fit: "图片适配", visible: "是否显示",
  when: "显示条件", condition: "判断条件", equals: "等于", items: "循环数据", as: "变量名",
  "maximum-items": "最多项数", direction: "排列方向", gap: "间距", columns: "列数",
  "column-gap": "列间距", "row-gap": "行间距", "cell-width": "单元宽度", "cell-height": "单元高度",
  "item-offset-x": "每项 X 偏移", "item-offset-y": "每项 Y 偏移", clip: "裁剪子图层",
  value: "当前数值", maximum: "最大数值", "gradient-start": "渐变起点", "gradient-end": "渐变终点"
};

function element(tag, className, text) {
  const el = document.createElement(tag);
  if (className) el.className = className;
  if (text != null) el.textContent = text;
  return el;
}

function icon(name) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.classList.add("icon");
  svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS(svg.namespaceURI, "use");
  use.setAttribute("href", "#i-" + name);
  svg.append(use);
  return svg;
}

function status(message, error = false) {
  const el = $("#status");
  el.textContent = message;
  el.title = message;
  el.classList.toggle("error", error);
}

async function api(path, options = {}) {
  const response = await fetch("/api/" + path, {
    ...options, headers: { ...options.headers, "X-ShitBot-Editor": "1" }
  });
  if (!response.ok) {
    let message = response.statusText;
    try { message = (await response.json()).error || message; } catch (_) { /* Non-JSON error response. */ }
    if (response.status === 401) message = "登录已过期。请在新标签页打开新的 /shitbot editor 链接，再回到此页面保存；当前修改仍保留。";
    throw new Error(message || "请求失败，请稍后重试");
  }
  return response;
}

function jsonRequest(method, value) {
  return { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(value) };
}

async function perform(action) {
  if (state.busy || state.gesture) return;
  try {
    flushEdits();
    state.busy = true;
    $("main").inert = true;
    syncChrome();
    await action();
  } catch (error) {
    status(error.message, true);
    if ($("#newTemplateDialog").open) $("#newTemplateError").textContent = error.message;
  } finally {
    state.busy = false;
    $("main").inert = false;
    syncChrome();
  }
}

function documentKey(value = state.bundle) {
  return value ? JSON.stringify({ manifest: value.manifest, scene: value.scene }) : "";
}

function hasChanges() {
  return !!state.bundle && (documentKey() !== state.saved || state.sourceDirty || state.jsonDirty || !!$("#fields [data-dirty]"));
}

function snapshot() {
  return { manifest: clone(state.bundle.manifest), scene: clone(state.bundle.scene), selection: clone(state.selection) };
}

function recordChange(before, message) {
  if (documentKey(before) === documentKey()) return;
  state.history.push(before);
  if (state.history.length > 100) state.history.shift();
  state.future = [];
  if (message) status(message);
}

function restore(value) {
  state.bundle.manifest = clone(value.manifest);
  state.bundle.scene = clone(value.scene);
  state.selection = clone(value.selection).filter(path => nodeAt(path));
  state.jsonDirty = false;
}

function mutate(change, message) {
  if (!state.bundle || state.tab !== "visual") return;
  const before = snapshot();
  change();
  recordChange(before, message);
  renderVisual();
}

function undo(redo = false) {
  const from = redo ? state.future : state.history;
  const to = redo ? state.history : state.future;
  if (!from.length || state.tab !== "visual") return;
  to.push(snapshot());
  restore(from.pop());
  renderVisual();
  status(redo ? "已重做" : "已撤销");
}

function syncChrome() {
  const loaded = !!state.bundle;
  const dirty = hasChanges();
  const visual = loaded && state.tab === "visual" && !state.busy;
  const selected = state.selection.length > 0;
  const saveState = $("#saveState");
  saveState.textContent = state.busy ? "处理中…" : !loaded ? "未打开模板" : dirty ? "未保存" : "已保存";
  saveState.className = "save-state " + (dirty ? "dirty" : loaded ? "saved" : "");
  if (loaded) {
    const title = state.bundle.manifest.name || state.id;
    $("#templateTitle").textContent = title;
    $("#templateTitle").title = title + " · " + state.id;
    document.title = (dirty ? "● " : "") + title + " · ShitBot 模板工作室";
  }
  for (const id of ["save", "preview", "publish", "upload", "resolveData"]) $("#" + id).disabled = !loaded || state.busy;
  for (const id of ["deleteLayer", "duplicateLayer", "layerUp", "layerDown"]) $("#" + id).disabled = !visual || !selected;
  for (const id of ["zoomIn", "zoomOut", "zoomReset", "zoomFit", "snap"]) $("#" + id).disabled = !visual;
  $("#newTemplate").disabled = state.busy;
  $("#newTemplateForm button[type=submit]").disabled = state.busy;
  $("#rollback").disabled = !loaded || state.busy || !$("#versions").value;
  $("#undo").disabled = !visual || !state.history.length;
  $("#redo").disabled = !visual || !state.future.length;
  $(".insert-controls").inert = !visual;
  $(".layer-panel").inert = !visual;
  $("#manifestSource").disabled = !loaded;
  $("#sceneSource").disabled = !loaded;
  const node = state.selection.length === 1 ? nodeAt(state.selection[0]) : null;
  for (const option of $("#insertTarget").options) {
    option.disabled = option.value !== "root" && !(node && Array.isArray(node[option.value]));
  }
  if ($("#insertTarget").selectedOptions[0]?.disabled) $("#insertTarget").value = "root";
}

async function refreshTemplates() {
  state.templates = await (await api("templates")).json();
  renderTemplates();
}

function renderTemplates() {
  const box = $("#templates");
  box.replaceChildren();
  $("#templateCount").textContent = state.templates.length;
  const search = $("#templateSearch").value.trim().toLocaleLowerCase();
  for (const item of state.templates) {
    const name = item.id === state.id ? state.bundle.manifest.name || item.id : item.name || item.id;
    if (!(name + " " + item.id).toLocaleLowerCase().includes(search)) continue;
    const button = element("button", "template" + (item.id === state.id ? " active" : ""));
    button.setAttribute("aria-pressed", String(item.id === state.id));
    button.title = name + " · " + item.id;
    const thumbnail = element("span", "template-icon");
    thumbnail.append(icon("image"));
    const info = element("span", "template-info");
    info.append(element("b", "", name), element("small", "", item.id));
    button.append(thumbnail, info, element("span", "template-version", item.published ? "v" + item.version : "草稿"));
    button.onclick = () => perform(() => openTemplate(item.id));
    box.append(button);
  }
  if (!box.children.length) box.append(element("p", "panel-empty", search ? "没有匹配的模板" : "还没有模板，点击 ＋ 创建"));
}

async function openTemplate(id) {
  if (id === state.id) return;
  if (hasChanges()) await save();
  status("正在加载模板…");
  const [bundle, sources] = await Promise.all([
    api("template?id=" + query(id)).then(response => response.json()),
    api("source?id=" + query(id)).then(response => response.json())
  ]);
  state.id = id;
  state.bundle = bundle;
  state.sources = sources;
  state.selection = [];
  state.history = [];
  state.future = [];
  state.saved = documentKey();
  state.sourceDirty = false;
  state.jsonDirty = false;
  state.fit = true;
  $("#layerSearch").value = "";
  $("#resolvedData").textContent = "解析后的数据将显示在这里。";
  updateSources();
  renderVersions();
  renderTemplates();
  renderVisual();
  status("草稿已打开 · 修改后按 Ctrl+S 保存");
}

function updateSources() {
  $("#manifestSource").value = state.sources.manifest;
  $("#sceneSource").value = state.sources.scene;
}

function renderVersions() {
  const select = $("#versions");
  const previous = select.value;
  select.replaceChildren();
  const versions = [...(state.bundle?.versions || [])].sort((a, b) => b - a);
  if (!versions.length) select.add(new Option("尚未发布", ""));
  for (const version of versions) select.add(new Option("已发布 v" + version, version));
  if (versions.some(version => String(version) === previous)) select.value = previous;
}

function layers() { return state.bundle?.scene.layers || []; }
function pathKey(path) { return JSON.stringify(path); }
function samePath(a, b) { return !!a && !!b && a.length === b.length && a.every((part, i) => part === b[i]); }
function nodeAt(path) { return path?.reduce((value, part) => value?.[part], layers()); }
function parentList(path) { return nodeAt(path.slice(0, -1)); }
function isSelected(path) { return state.selection.some(selected => samePath(selected, path)); }

function flatten(nodes = layers(), prefix = [], depth = 0, out = [], reverse = false) {
  const indices = nodes.map((_, i) => i);
  if (reverse) indices.reverse();
  for (const index of indices) {
    const node = nodes[index];
    if (!node || typeof node !== "object") continue;
    const path = [...prefix, index];
    out.push({ node, path, depth });
    for (const key of ["children", "then", "else"]) {
      if (Array.isArray(node[key])) flatten(node[key], [...path, key], depth + 1, out, reverse);
    }
  }
  return out;
}

function rootSelection() {
  return flatten().filter(item => isSelected(item.path) && !state.selection.some(path =>
    path.length < item.path.length && path.every((part, i) => part === item.path[i])));
}

function selectNodes(nodes) {
  state.selection = flatten().filter(item => nodes.includes(item.node)).map(item => item.path);
}

function selectLayer(path, additive = false) {
  if (additive) {
    state.selection = isSelected(path) ? state.selection.filter(selected => !samePath(selected, path)) : [...state.selection, path];
  } else state.selection = path ? [path] : [];
  state.jsonDirty = false;
  $("#insertTarget").value = "root";
  renderVisual();
}

function layerName(node) { return String(node.name || node.text || layerTypes[node.type]?.[0] || node.type || "图层"); }
function number(value, fallback = 0) { return value !== "" && value != null && Number.isFinite(Number(value)) ? Number(value) : fallback; }
function bound(value) { return typeof value === "string" && value.includes("${"); }
function defaultSize(type) {
  if (type === "text") return [240, 36];
  if (type === "line") return [160, 2];
  if (type === "circle" || type === "avatar") return [80, 80];
  if (type === "progress") return [240, 20];
  return [200, 120];
}

function nodeSize(node) {
  const defaults = defaultSize(node.type);
  return [Math.max(1, number(node.width, number(node.diameter, defaults[0]))),
    Math.max(1, number(node.height, node.type === "text" ? number(node["font-size"], 18) * 1.4 :
      node.type === "circle" ? number(node.width, number(node.diameter, defaults[1])) : defaults[1]))];
}

// The renderer uses #AARRGGBB; CSS uses #RRGGBBAA.
function cssColor(value, fallback = "transparent") {
  if (typeof value !== "string" || bound(value)) return fallback;
  if (/^#[0-9a-f]{8}$/i.test(value)) return "#" + value.slice(3) + value.slice(1, 3);
  return CSS.supports("color", value) ? value : fallback;
}

function renderVisual() {
  state.selection = state.selection.filter(path => nodeAt(path));
  renderStage();
  renderLayerList();
  renderProperties();
  syncChrome();
}

function renderStage() {
  $("#emptyCanvas").hidden = !!state.bundle;
  $("#stageFrame").hidden = !state.bundle;
  if (!state.bundle) return;
  const canvas = state.bundle.scene.canvas || {};
  const width = number(canvas.width, 1200), height = number(canvas.height, 675);
  const wrap = $(".stage-wrap");
  if (state.fit && state.tab === "visual") {
    state.zoom = Math.max(.05, Math.min(1, (wrap.clientWidth - 96) / width, (wrap.clientHeight - 96) / height));
  }
  const stage = $("#stage");
  stage.style.width = width + "px";
  stage.style.height = height + "px";
  stage.style.transform = `scale(${state.zoom})`;
  stage.style.setProperty("--zoom", state.zoom);
  $("#stageFrame").style.width = width * state.zoom + "px";
  $("#stageFrame").style.height = height * state.zoom + "px";
  stage.style.background = canvas["gradient-start"] && canvas["gradient-end"]
    ? `linear-gradient(${90 + Math.atan2(height, width) * 180 / Math.PI}deg, ${cssColor(canvas["gradient-start"])}, ${cssColor(canvas["gradient-end"])})`
    : cssColor(canvas.background);
  stage.replaceChildren();
  drawNodes(stage, layers());
  $("#canvasSize").textContent = width + " × " + height;
  $("#zoomReset").textContent = Math.round(state.zoom * 100) + "%";
  $("#zoomFit").classList.toggle("active", state.fit);
  $("#selectionInfo").textContent = state.selection.length > 1 ? "已选择 " + state.selection.length + " 个图层" :
    state.selection.length ? layerName(nodeAt(state.selection[0])) : "布局视图 · 动态数据与图片请查看预览";
}

function structuralChildren(nodes, prefix, out = []) {
  nodes.forEach((node, index) => {
    if (!node || typeof node !== "object" || node.visible === false || node.visible === "false") return;
    const path = [...prefix, index];
    if (node.type === "condition" || node.type === "loop") {
      // Show one structural instance; provider evaluation belongs to the real preview.
      for (const key of ["then", "else", "children"]) {
        if (Array.isArray(node[key])) structuralChildren(node[key], [...path, key], out);
      }
    } else out.push({ node, path });
  });
  return out;
}

function drawNodes(parent, nodes, prefix = [], layout = null) {
  let cursor = 0;
  structuralChildren(nodes, prefix).forEach(({ node, path }, index) => {
    const [width, height] = nodeSize(node);
    let x = number(node.x), y = number(node.y);
    if (layout?.type === "stack") {
      const horizontal = layout.direction === "horizontal";
      if (horizontal) x += cursor; else y += cursor;
      cursor += (horizontal ? width : height) + number(layout.gap);
    } else if (layout?.type === "grid") {
      const columns = Math.max(1, number(layout.columns, 1));
      x += index % columns * (number(layout["cell-width"], width) + number(layout["column-gap"]));
      y += Math.floor(index / columns) * (number(layout["cell-height"], height) + number(layout["row-gap"]));
    }
    const el = element("div", "stage-node" + (isSelected(path) ? " selected" : ""));
    el.dataset.path = pathKey(path);
    el.dataset.type = node.type;
    el.title = layerName(node);
    Object.assign(el.style, { left: x + "px", top: y + "px", width: width + "px", height: height + "px", opacity: Math.max(0, Math.min(1, number(node.opacity, 1))) });
    if (node.radius != null) el.style.borderRadius = number(node.radius) + "px";
    if (node.type === "text") {
      el.textContent = node.text ?? "文字";
      Object.assign(el.style, {
        color: cssColor(node.color, "#ffffff"), fontSize: number(node["font-size"], 18) + "px",
        fontWeight: String(node["font-style"]).includes("bold") ? "700" : "400",
        fontStyle: String(node["font-style"]).includes("italic") ? "italic" : "normal",
        textAlign: node.align || "left", lineHeight: node["line-height"] ? number(node["line-height"], 24) + "px" : "1.2",
        fontFamily: node["font-family"] && node["font-family"] !== "SansSerif" ? node["font-family"] : "sans-serif"
      });
    } else if (node.type === "image" || node.type === "avatar") {
      el.append(icon("image"));
      el.title = layerName(node) + " · " + (node.source || "请设置图片地址") + "（实际图片见预览）";
    } else if (node.type === "progress") {
      const ratio = number(node.maximum, 100) <= 0 ? 0 : Math.max(0, Math.min(100, number(node.value) / number(node.maximum, 100) * 100));
      el.style.background = `linear-gradient(90deg, ${cssColor(node.fill, "#4ecb8c")} ${ratio}%, ${cssColor(node.background, "#ffffff30")} ${ratio}%)`;
      el.style.borderRadius = number(node.radius, height / 2) + "px";
    } else if (node.type === "line") {
      const line = element("div", "line-stroke");
      const dx = node.x2 != null ? number(node.x2) - number(node.x) : number(node.width, 160);
      const dy = node.y2 != null ? number(node.y2) - number(node.y) : number(node.height);
      Object.assign(line.style, { width: Math.hypot(dx, dy) + "px", height: Math.max(.1, number(node["stroke-width"], 1)) + "px", background: cssColor(node.color || node["stroke-color"], "white"), transform: `rotate(${Math.atan2(dy, dx)}rad)` });
      el.append(line);
    } else if (!["group", "stack", "grid"].includes(node.type)) {
      el.style.background = cssColor(node.fill || node.color);
    }
    if (node["stroke-color"] && node.type !== "line") {
      el.style.border = number(node["stroke-width"], 1) + "px solid " + cssColor(node["stroke-color"]);
    }
    if (Array.isArray(node.children)) {
      if (node.clip === true || node.clip === "true") el.style.overflow = "hidden";
      drawNodes(el, node.children, [...path, "children"], node);
    }
    if (isSelected(path) && state.selection.length === 1) {
      const handle = element("span", "resize-handle");
      handle.title = "拖动调整大小 · Shift 保持比例";
      el.append(handle);
    }
    parent.append(el);
  });
}

function renderLayerList() {
  const box = $("#layers");
  box.replaceChildren();
  const flat = flatten(layers(), [], 0, [], true);
  const search = $("#layerSearch").value.trim().toLocaleLowerCase();
  $("#layerCount").textContent = flat.length;
  for (const item of flat) {
    if (!(layerName(item.node) + " " + item.node.type).toLocaleLowerCase().includes(search)) continue;
    const el = element("button", "layer" + (isSelected(item.path) ? " selected" : "") + (item.node.visible === false || item.node.visible === "false" ? " layer-hidden" : ""));
    el.dataset.path = pathKey(item.path);
    el.style.paddingLeft = 8 + item.depth * 14 + "px";
    el.title = layerName(item.node) + " · " + (layerTypes[item.node.type]?.[0] || item.node.type);
    el.setAttribute("aria-pressed", String(isSelected(item.path)));
    el.append(element("span", "layer-type", layerTypes[item.node.type]?.[1] || "◇"), element("span", "layer-label", layerName(item.node)));
    if (["then", "else"].includes(item.path.at(-2))) el.append(element("span", "branch-tag", item.path.at(-2) === "then" ? "成立" : "否则"));
    el.onclick = event => {
      try {
        flushEdits();
        selectLayer(item.path, event.shiftKey || event.ctrlKey || event.metaKey);
        $(".stage-wrap").focus({ preventScroll: true });
      } catch (error) { status(error.message, true); }
    };
    box.append(el);
  }
  if (!box.children.length) box.append(element("p", "panel-empty", search ? "没有匹配的图层" : "画布还是空的，添加第一个图层吧"));
}

function fieldGroup(title, target, names, defaults = {}) {
  const section = element("section", "property-section");
  section.append(element("h3", "", title));
  const grid = element("div", "field-grid");
  for (const name of names) grid.append(propertyField(target, name, defaults[name]));
  section.append(grid);
  $("#fields").append(section);
}

function propertyField(target, name, placeholder) {
  const isColor = ["fill", "color", "background", "stroke-color", "gradient-start", "gradient-end"].includes(name);
  const wide = isColor || ["name", "text", "source", "font-family", "when", "condition", "items", "equals"].includes(name);
  const label = element("label", wide ? "wide-field" : "");
  label.append(element("span", "", fieldLabels[name] || name));
  const input = element(name === "text" ? "textarea" : "input", name === "text" ? "text-field" : "");
  input.id = "field-" + name;
  label.htmlFor = input.id;
  input.value = target[name] == null ? "" : typeof target[name] === "object" ? JSON.stringify(target[name]) : target[name];
  input.placeholder = placeholder == null ? "默认" : String(placeholder);
  input.dataset.field = name;
  input.title = name;
  if (numericFields.has(name)) input.inputMode = "decimal";
  const enums = {
    "font-style": ["plain", "bold", "italic", "bold italic"], align: ["left", "center", "right"],
    direction: ["vertical", "horizontal"], fit: ["cover", "contain", "stretch"], visible: ["true", "false"], clip: ["true", "false"]
  };
  if (enums[name]) {
    const choices = element("datalist");
    choices.id = "choices-" + name;
    for (const value of enums[name]) choices.append(new Option(value, value));
    input.setAttribute("list", choices.id);
    label.append(choices);
  }
  input.oninput = () => { input.dataset.dirty = "true"; syncChrome(); };
  input.commitField = () => {
    if (!input.dataset.dirty) return;
    const raw = name === "text" ? input.value : input.value.trim();
    let value = raw;
    if (numericFields.has(name) && raw !== "") {
      if (bound(raw) && target !== state.bundle.scene.canvas) value = raw;
      else {
        value = Number(raw);
        if (!Number.isFinite(value)) throw new Error((fieldLabels[name] || name) + "需要填写数字或变量表达式");
        if (target === state.bundle.scene.canvas && (!Number.isInteger(value) || value <= 0)) throw new Error("画布尺寸必须是大于 0 的整数");
        if (name === "opacity" && (value < 0 || value > 1)) throw new Error("不透明度应在 0 到 1 之间");
      }
    }
    if ((name === "visible" || name === "clip") && /^(true|false)$/.test(raw)) value = raw === "true";
    if (raw === "" && target === state.bundle.scene.canvas && ["width", "height"].includes(name)) throw new Error("请填写画布尺寸");
    const before = snapshot();
    if (raw === "" && name !== "text") delete target[name]; else target[name] = value;
    delete input.dataset.dirty;
    input.removeAttribute("aria-invalid");
    if (isColor) {
      const color = cssColor(target[name], "#ffffff");
      const picker = input.parentElement.querySelector("input[type=color]");
      if (picker && /^#[0-9a-f]{6,8}$/i.test(color)) picker.value = color.slice(0, 7);
    }
    recordChange(before);
    status("已更新" + (fieldLabels[name] || name));
    if (!state.jsonDirty && state.selection.length === 1) $("#layerJson").value = JSON.stringify(nodeAt(state.selection[0]), null, 2);
    renderStage();
    // Keep list buttons in place when an input blurs into a layer click.
    for (const layer of $$("#layers .layer")) {
      const current = nodeAt(JSON.parse(layer.dataset.path));
      if (!current) continue;
      layer.querySelector(".layer-label").textContent = layerName(current);
      layer.title = layerName(current) + " · " + (layerTypes[current.type]?.[0] || current.type);
      layer.classList.toggle("layer-hidden", current.visible === false || current.visible === "false");
    }
    syncChrome();
  };
  input.onchange = () => {
    try { input.commitField(); } catch (error) { input.setAttribute("aria-invalid", "true"); status(error.message, true); }
  };
  if (isColor) {
    const row = element("div", "color-field");
    const picker = element("input", "color-picker");
    picker.type = "color";
    const color = cssColor(target[name], "#ffffff");
    picker.value = /^#[0-9a-f]{6,8}$/i.test(color) ? color.slice(0, 7) : "#ffffff";
    picker.setAttribute("aria-label", "选择" + fieldLabels[name]);
    picker.title = "选择颜色（保留原有透明度）；文本支持 #AARRGGBB 和变量";
    picker.onchange = () => {
      const alpha = /^#[0-9a-f]{8}$/i.test(target[name] || "") ? target[name].slice(1, 3) : "";
      input.value = "#" + alpha + picker.value.slice(1);
      input.dataset.dirty = "true";
      input.commitField();
    };
    row.append(picker, input);
    label.append(row);
  } else label.append(input);
  return label;
}

function renderProperties() {
  const box = $("#fields");
  box.replaceChildren();
  $("#advancedProperties").hidden = state.selection.length !== 1;
  if (!state.bundle) {
    $("#inspectorTitle").textContent = "设计属性";
    $("#inspectorSubtitle").textContent = "你的下一张状态卡，从这里开始。";
    box.append(element("p", "inspector-note", "打开模板后，可在这里调整画布与图层的外观。"));
    return;
  }
  if (!state.selection.length) {
    $("#inspectorTitle").textContent = "画布设置";
    $("#inspectorSubtitle").textContent = "点击画布中的元素以编辑图层。";
    const canvas = state.bundle.scene.canvas;
    fieldGroup("画布尺寸", canvas, ["width", "height"]);
    fieldGroup("背景与渐变", canvas, ["background", "gradient-start", "gradient-end"]);
    box.append(element("p", "inspector-note", "设置渐变起点与终点可启用渐变；清空其中一项可回到背景色。颜色支持 #RRGGBB 与 #AARRGGBB。"));
    box.append(element("p", "inspector-note", "按住空格拖动画布，Ctrl + 滚轮缩放。实际图片与动态数据通过右上角预览查看。"));
    return;
  }
  if (state.selection.length > 1) {
    $("#inspectorTitle").textContent = state.selection.length + " 个图层";
    $("#inspectorSubtitle").textContent = "一起移动、复制或删除这些图层。";
    addAlignmentControls();
    box.append(element("p", "inspector-note", "方向键移动 1px，按住 Shift 移动 10px。按 Delete 直接删除，Ctrl+Z 撤销。选中父布局时，其子图层会随父布局一起操作。"));
    return;
  }
  const node = nodeAt(state.selection[0]);
  $("#inspectorTitle").textContent = layerTypes[node.type]?.[0] || "图层属性";
  $("#inspectorSubtitle").textContent = "位置与尺寸以像素为单位，支持变量绑定。";
  fieldGroup("图层", node, ["name"], { name: layerName(node) });
  if (!["condition", "loop"].includes(node.type)) {
    addAlignmentControls();
    const [width, height] = nodeSize(node);
    fieldGroup("位置与尺寸", node, ["x", "y", "width", "height"], { x: 0, y: 0, width, height });
  }
  if (node.type === "text") {
    fieldGroup("文字", node, ["text", "font-family", "font-size", "font-style", "align", "line-height", "maximum-lines", "color"], { "font-family": "SansSerif", "font-size": 18, "font-style": "plain", align: "left" });
  } else if (["image", "avatar"].includes(node.type)) {
    fieldGroup("图片", node, ["source", "fit", "radius", "stroke-color", "stroke-width"], { fit: "cover", radius: 0 });
  } else if (["rectangle", "circle", "line", "progress"].includes(node.type)) {
    const names = node.type === "line" ? ["x2", "y2", "color", "stroke-width"] : node.type === "progress"
      ? ["value", "maximum", "fill", "background", "radius"] : ["fill", "stroke-color", "stroke-width", "radius"];
    if (node.type === "circle" && "diameter" in node) names.push("diameter");
    fieldGroup("外观", node, names);
  }
  if (node.type === "stack") fieldGroup("堆叠布局", node, ["direction", "gap"], { direction: "vertical", gap: 0 });
  if (node.type === "grid") fieldGroup("网格布局", node, ["columns", "cell-width", "cell-height", "column-gap", "row-gap"], { columns: 1 });
  if (node.type === "group") fieldGroup("分组", node, ["clip", "radius"], { clip: "false" });
  if (node.type === "condition") fieldGroup("条件分支", node, ["condition", "equals"]);
  if (node.type === "loop") fieldGroup("循环", node, ["items", "as", "maximum-items", "item-offset-x", "item-offset-y"]);
  fieldGroup("显示", node, ["opacity", "visible", "when"], { opacity: 1, visible: "true" });
  if (["group", "stack", "grid", "condition", "loop"].includes(node.type)) {
    box.append(element("p", "inspector-note", "在顶部添加位置中选择容器或条件分支，可直接添加子图层。条件与循环在布局视图中展示结构示例，实际结果请预览。"));
  }
  if (!state.jsonDirty) $("#layerJson").value = JSON.stringify(node, null, 2);
}

function addAlignmentControls() {
  const section = element("section", "property-section");
  section.append(element("h3", "", state.selection.length > 1 ? "对齐所选图层" : "对齐画布"));
  const row = element("div", "alignment-controls");
  for (const [value, label, glyph] of [["left", "左对齐", "⇤"], ["center", "水平居中", "↔"], ["right", "右对齐", "⇥"], ["top", "顶部对齐", "↥"], ["middle", "垂直居中", "↕"], ["bottom", "底部对齐", "↧"]]) {
    const button = element("button", "icon-button", glyph);
    button.title = label;
    button.setAttribute("aria-label", label);
    button.dataset.align = value;
    row.append(button);
  }
  section.append(row);
  $("#fields").append(section);
}

function parseJson(text, label) {
  try { return JSON.parse(text || "{}"); }
  catch (error) { throw new Error(label + "的 JSON 格式有误：" + error.message); }
}

function validateLayer(node, depth = 0) {
  if (!node || typeof node !== "object" || Array.isArray(node) || !Object.hasOwn(layerTypes, node.type)) throw new Error("图层 JSON 必须是包含有效 type 的对象");
  if (depth > 32) throw new Error("图层嵌套不能超过 32 层");
  for (const key of ["children", "then", "else"]) {
    if (!(key in node)) continue;
    if (!Array.isArray(node[key])) throw new Error(key + " 必须是图层数组");
    node[key].forEach(child => validateLayer(child, depth + 1));
  }
}

function applyLayerJson() {
  if (state.selection.length !== 1 || !state.jsonDirty) return;
  const parsed = parseJson($("#layerJson").value, "图层");
  const path = state.selection[0];
  validateLayer(parsed, (path.length - 1) / 2);
  const before = snapshot();
  parentList(path)[path.at(-1)] = parsed;
  state.jsonDirty = false;
  recordChange(before, "已应用图层 JSON");
  renderVisual();
}

function flushEdits() {
  for (const input of $$("#fields [data-dirty]")) input.commitField();
  if (state.jsonDirty) applyLayerJson();
}

function deleteSelection() {
  const items = rootSelection();
  if (!items.length) return;
  mutate(() => {
    for (const item of items.reverse()) parentList(item.path).splice(item.path.at(-1), 1);
    state.selection = [];
  }, "已删除选中图层 · Ctrl+Z 可撤销");
}

function duplicateSelection() {
  const items = rootSelection();
  if (!items.length) return;
  mutate(() => {
    const copies = [];
    for (const item of items.reverse()) {
      const copy = clone(item.node);
      copy.name = layerName(item.node) + " 副本";
      if (!["condition", "loop"].includes(copy.type)) {
        if (!bound(copy.x)) {
          copy.x = number(copy.x) + 20;
          if (copy.type === "line" && copy.x2 != null && !bound(copy.x2)) copy.x2 = number(copy.x2) + 20;
        }
        if (!bound(copy.y)) {
          copy.y = number(copy.y) + 20;
          if (copy.type === "line" && copy.y2 != null && !bound(copy.y2)) copy.y2 = number(copy.y2) + 20;
        }
      }
      parentList(item.path).splice(item.path.at(-1) + 1, 0, copy);
      copies.push(copy);
    }
    selectNodes(copies);
  }, "已复制选中图层");
}

function reorderSelection(direction) {
  const items = rootSelection();
  const selected = new Set(items.map(item => item.node));
  const lists = new Set(items.map(item => parentList(item.path)));
  mutate(() => {
    for (const list of lists) {
      for (let i = direction > 0 ? list.length - 2 : 1; direction > 0 ? i >= 0 : i < list.length; i -= direction) {
        const next = i + direction;
        if (selected.has(list[i]) && !selected.has(list[next])) [list[i], list[next]] = [list[next], list[i]];
      }
    }
    selectNodes([...selected]);
  }, direction > 0 ? "图层已上移" : "图层已下移");
}

function movable(items, resize = false) {
  if (items.some(item => ["condition", "loop"].includes(item.node.type))) throw new Error("条件与循环节点本身没有位置，请选择其中的子图层移动");
  const fields = resize ? ["width", "height", "x2", "y2"] : ["x", "y", "x2", "y2"];
  if (items.some(item => fields.some(field => bound(item.node[field])))) throw new Error("位置或尺寸使用了变量绑定，请先在属性中改为固定数值");
}

function moveNode(node, x, y) {
  if (node.type === "line") {
    if (node.x2 != null) node.x2 = number(node.x2) + x - number(node.x);
    if (node.y2 != null) node.y2 = number(node.y2) + y - number(node.y);
  }
  node.x = x;
  node.y = y;
}

function nudge(dx, dy) {
  const items = rootSelection();
  if (!items.length) return;
  movable(items);
  mutate(() => items.forEach(item => moveNode(item.node, number(item.node.x) + dx, number(item.node.y) + dy)), "已微调图层位置");
}

function alignSelection(alignment) {
  const items = rootSelection();
  if (!items.length) return;
  movable(items);
  const stageRect = $("#stage").getBoundingClientRect();
  const boxes = items.map(item => {
    const el = $$(".stage-node").find(node => node.dataset.path === pathKey(item.path));
    if (!el) throw new Error("隐藏或动态节点暂不能对齐，请选择画布中可见的图层");
    const rect = el.getBoundingClientRect();
    return { ...item, x: (rect.left - stageRect.left) / state.zoom, y: (rect.top - stageRect.top) / state.zoom, width: rect.width / state.zoom, height: rect.height / state.zoom };
  });
  const canvas = state.bundle.scene.canvas;
  const left = boxes.length === 1 ? 0 : Math.min(...boxes.map(box => box.x));
  const top = boxes.length === 1 ? 0 : Math.min(...boxes.map(box => box.y));
  const right = boxes.length === 1 ? canvas.width : Math.max(...boxes.map(box => box.x + box.width));
  const bottom = boxes.length === 1 ? canvas.height : Math.max(...boxes.map(box => box.y + box.height));
  mutate(() => {
    for (const box of boxes) {
      let x = box.x, y = box.y;
      if (alignment === "left") x = left;
      if (alignment === "center") x = (left + right - box.width) / 2;
      if (alignment === "right") x = right - box.width;
      if (alignment === "top") y = top;
      if (alignment === "middle") y = (top + bottom - box.height) / 2;
      if (alignment === "bottom") y = bottom - box.height;
      moveNode(box.node, Math.round(number(box.node.x) + x - box.x), Math.round(number(box.node.y) + y - box.y));
    }
  }, "已对齐图层");
}

function defaultLayer(type) {
  const [width, height] = defaultSize(type);
  const node = { type, name: layerTypes[type][0], x: 24, y: 24, width, height };
  if (type === "text") Object.assign(node, { text: "新的文字", "font-size": 24, color: "#FFFFFFFF" });
  else if (["rectangle", "circle"].includes(type)) Object.assign(node, { fill: "#80E2C0", ...(type === "rectangle" ? { radius: 12 } : {}) });
  else if (["image", "avatar"].includes(type)) node.source = "assets/example.png";
  else if (type === "line") Object.assign(node, { height: 0, color: "#FFFFFFFF", "stroke-width": 2 });
  else if (type === "progress") Object.assign(node, { value: 60, maximum: 100, fill: "#80E2C0" });
  else if (type === "condition") return { type, name: "条件", condition: "${data.example.enabled}", then: [], else: [] };
  else if (type === "loop") return { type, name: "循环", items: "${data.example.items}", as: "item", "maximum-items": 20, children: [] };
  else {
    node.children = [];
    if (type === "stack") Object.assign(node, { direction: "vertical", gap: 12 });
    if (type === "grid") Object.assign(node, { columns: 2, "cell-width": 120, "cell-height": 80, "column-gap": 12, "row-gap": 12 });
  }
  return node;
}

function addLayer() {
  const target = $("#insertTarget").value;
  const parent = state.selection.length === 1 ? nodeAt(state.selection[0]) : null;
  const list = target === "root" ? layers() : parent?.[target];
  if (!Array.isArray(list)) throw new Error("请先选择可以添加子图层的布局或分支");
  mutate(() => {
    const node = defaultLayer($("#newLayerType").value);
    list.push(node);
    selectNodes([node]);
    $("#layerSearch").value = "";
  }, "已添加图层");
}

function startGesture(event) {
  if (state.busy || state.gesture || !event.isPrimary || state.tab !== "visual" || !state.bundle || ![0, 1].includes(event.button)) return;
  const wrap = $(".stage-wrap");
  const hit = event.target.closest(".stage-node");
  const path = hit ? JSON.parse(hit.dataset.path) : null;
  const resizing = !!event.target.closest(".resize-handle");
  try {
    flushEdits();
    wrap.focus({ preventScroll: true });
    if (state.space || event.button === 1) {
      event.preventDefault();
      state.gesture = { kind: "pan", pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, left: wrap.scrollLeft, top: wrap.scrollTop };
      wrap.classList.add("panning");
    } else if (path) {
      event.preventDefault();
      if (!resizing && (event.shiftKey || event.ctrlKey || event.metaKey)) {
        selectLayer(path, true);
        return;
      }
      if (!isSelected(path)) selectLayer(path);
      const items = rootSelection();
      movable(items, resizing);
      state.gesture = {
        kind: resizing ? "resize" : "move", pointerId: event.pointerId,
        startX: event.clientX, startY: event.clientY, before: snapshot(), moved: false,
        items: items.map(item => ({ ...item, x: number(item.node.x), y: number(item.node.y), size: nodeSize(item.node) }))
      };
    } else {
      selectLayer(null);
      return;
    }
    wrap.setPointerCapture(event.pointerId);
  } catch (error) { status(error.message, true); }
}

function moveGesture(event) {
  const gesture = state.gesture;
  if (!gesture || gesture.pointerId !== event.pointerId) return;
  const wrap = $(".stage-wrap");
  const distanceX = event.clientX - gesture.startX, distanceY = event.clientY - gesture.startY;
  if (gesture.kind === "pan") {
    wrap.scrollLeft = gesture.left - distanceX;
    wrap.scrollTop = gesture.top - distanceY;
    return;
  }
  if (!gesture.moved && Math.hypot(distanceX, distanceY) < 3) return;
  gesture.moved = true;
  const step = state.snap && !event.altKey ? 5 : 1;
  const snap = value => Math.round(value / step) * step;
  const dx = distanceX / state.zoom, dy = distanceY / state.zoom;
  const first = gesture.items[0];
  if (gesture.kind === "resize") {
    let width = Math.max(1, snap(first.size[0] + dx)), height = Math.max(1, snap(first.size[1] + dy));
    if (event.shiftKey) {
      const ratio = Math.abs(dx / first.size[0]) >= Math.abs(dy / first.size[1]) ? width / first.size[0] : height / first.size[1];
      width = Math.max(1, Math.round(first.size[0] * ratio));
      height = Math.max(1, Math.round(first.size[1] * ratio));
    }
    first.node.width = width;
    first.node.height = height;
    if (first.node.type === "line") {
      if (first.node.x2 != null) first.node.x2 = number(first.node.x) + width;
      if (first.node.y2 != null) first.node.y2 = number(first.node.y) + height;
    }
  } else {
    const offsetX = snap(first.x + dx) - first.x, offsetY = snap(first.y + dy) - first.y;
    for (const item of gesture.items) moveNode(item.node, item.x + offsetX, item.y + offsetY);
  }
  renderStage();
  syncChrome();
}

function finishGesture(cancel = false) {
  const gesture = state.gesture;
  if (!gesture) return;
  state.gesture = null;
  const wrap = $(".stage-wrap");
  wrap.classList.remove("panning");
  if (wrap.hasPointerCapture(gesture.pointerId)) wrap.releasePointerCapture(gesture.pointerId);
  if (gesture.kind === "pan") return;
  if (cancel) restore(gesture.before);
  else if (gesture.moved) recordChange(gesture.before, gesture.kind === "resize" ? "已调整图层尺寸" : "已移动图层");
  renderVisual();
}

function zoomTo(value, anchor = null) {
  if (!state.bundle || state.tab !== "visual") return;
  const wrap = $(".stage-wrap"), rect = wrap.getBoundingClientRect();
  const point = anchor || { x: rect.left + wrap.clientWidth / 2, y: rect.top + wrap.clientHeight / 2 };
  const before = $("#stage").getBoundingClientRect();
  const x = (point.x - before.left) / state.zoom, y = (point.y - before.top) / state.zoom;
  state.fit = false;
  state.zoom = Math.max(.05, Math.min(4, value));
  renderStage();
  const after = $("#stage").getBoundingClientRect();
  wrap.scrollLeft += after.left + x * state.zoom - point.x;
  wrap.scrollTop += after.top + y * state.zoom - point.y;
}

function previewBody() {
  const context = parseJson($("#contextData").value, "上下文");
  const data = parseJson($("#overrideData").value, "数据覆盖");
  for (const value of [context, data]) {
    if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("预览上下文与数据覆盖必须是 JSON 对象");
  }
  return { context, data };
}

async function save() {
  if (!state.id) return;
  if (documentKey() === state.saved && !state.sourceDirty) { status("草稿已保存"); return; }
  status("正在保存草稿…");
  const before = snapshot();
  if (state.sourceDirty) {
    await api("source?id=" + query(state.id), jsonRequest("PUT", {
      manifest: $("#manifestSource").value, scene: $("#sceneSource").value
    }));
    const [bundle, sources] = await Promise.all([
      api("template?id=" + query(state.id)).then(response => response.json()),
      api("source?id=" + query(state.id)).then(response => response.json())
    ]);
    state.bundle = bundle;
    state.sources = sources;
    state.sourceDirty = false;
    state.selection = [];
    recordChange(before);
  } else {
    await api("draft?id=" + query(state.id), jsonRequest("PUT", { manifest: state.bundle.manifest, scene: state.bundle.scene }));
    state.sources = await (await api("source?id=" + query(state.id))).json();
  }
  state.saved = documentKey();
  updateSources();
  renderTemplates();
  renderVisual();
  status("草稿已保存");
}

async function switchTab(tab) {
  if (state.tab === tab) return;
  if (state.bundle && (state.sourceDirty || (tab === "source" && documentKey() !== state.saved))) await save();
  state.tab = tab;
  for (const button of $$("button[data-tab]")) {
    const active = button.dataset.tab === tab;
    button.classList.toggle("active", active);
    button.setAttribute("aria-selected", String(active));
    button.tabIndex = active ? 0 : -1;
  }
  for (const panel of $$(".tab")) panel.classList.toggle("active", panel.id === tab);
  if (tab === "visual") renderVisual();
  syncChrome();
}

async function preview() {
  if (!state.id) return;
  const body = previewBody();
  await save();
  status("正在渲染预览…");
  const response = await api("preview?id=" + query(state.id), jsonRequest("POST", body));
  const blob = await response.blob();
  if (state.previewUrl) URL.revokeObjectURL(state.previewUrl);
  state.previewUrl = URL.createObjectURL(blob);
  $("#previewImage").src = state.previewUrl;
  $("#downloadPreview").href = state.previewUrl;
  $("#downloadPreview").download = state.id + ".png";
  $("#previewDialog").showModal();
  status("预览已生成");
}

async function publish() {
  if (!state.id) return;
  await save();
  status("正在发布…");
  const result = await (await api("publish?id=" + query(state.id), { method: "POST" })).json();
  state.bundle.versions = [...new Set([...(state.bundle.versions || []), result.version])];
  renderVersions();
  await refreshTemplates();
  status("已发布版本 v" + result.version);
}

async function rollback() {
  const version = $("#versions").value;
  if (!state.id || !version || !confirm("将正在使用的模板回滚到 v" + version + "？当前草稿会保留。")) return;
  await api("rollback?id=" + query(state.id) + "&version=" + query(version), { method: "POST" });
  await refreshTemplates();
  status("已将发布版本回滚到 v" + version);
}

async function upload() {
  if (!state.id) return;
  const file = $("#asset").files[0], name = $("#assetName").value.trim();
  if (!file || !name) throw new Error("请选择图片并填写资源路径");
  await api("asset?id=" + query(state.id) + "&name=" + query(name), {
    method: "PUT", headers: { "Content-Type": "application/octet-stream" }, body: file
  });
  status("图片已上传，图层地址填写：assets/" + name);
}

async function resolveData() {
  if (!state.id) return;
  const body = previewBody();
  await save();
  status("正在解析数据提供器…");
  const result = await (await api("resolve?id=" + query(state.id), jsonRequest("POST", body))).json();
  $("#resolvedData").textContent = JSON.stringify(result, null, 2);
  status("数据提供器解析完成");
}

function openNewTemplate() {
  $("#newTemplateForm").reset();
  $("#newTemplateError").textContent = "";
  $("#newTemplateDialog").showModal();
}

async function createTemplate() {
  const id = $("#newTemplateId").value.trim(), name = $("#newTemplateName").value.trim();
  if (!name || !/^[a-z0-9][a-z0-9_-]{0,63}$/.test(id)) throw new Error("请填写模板名称；ID 以小写字母或数字开头，最多 64 个字符");
  if (hasChanges()) await save();
  await api("template?id=" + query(id), jsonRequest("POST", { name }));
  await refreshTemplates();
  await openTemplate(id);
  $("#templateSearch").value = "";
  renderTemplates();
  $("#newTemplateDialog").close();
  await switchTab("visual");
  status("新模板已创建");
}

const actions = {
  save, preview, publish, rollback, upload, resolveData, newTemplate: openNewTemplate,
  addLayer, deleteLayer: deleteSelection, duplicateLayer: duplicateSelection,
  layerUp: () => reorderSelection(1), layerDown: () => reorderSelection(-1),
  undo: () => undo(), redo: () => undo(true), applyLayerJson,
  zoomIn: () => zoomTo(state.zoom * 1.2), zoomOut: () => zoomTo(state.zoom / 1.2),
  zoomReset: () => zoomTo(1), zoomFit: () => { state.fit = true; renderStage(); },
  snap: () => {
    state.snap = !state.snap;
    $("#snap").classList.toggle("active", state.snap);
    $("#snap").setAttribute("aria-pressed", String(state.snap));
    status(state.snap ? "已开启 5px 吸附 · Alt 拖动临时关闭" : "已关闭吸附");
  },
  showShortcuts: () => $("#shortcutsDialog").showModal(),
  closePreview: () => $("#previewDialog").close()
};
const remoteActions = new Set(["save", "preview", "publish", "rollback", "upload", "resolveData"]);

document.addEventListener("click", event => {
  const button = event.target.closest("button");
  if (!button || button.disabled || state.busy || state.gesture) return;
  if (button.dataset.close) { $("#" + button.dataset.close).close(); return; }
  if (button.dataset.tab) { perform(() => switchTab(button.dataset.tab)); return; }
  const id = button.dataset.action || button.id;
  if (!actions[id] && !button.dataset.align) return;
  if (remoteActions.has(id)) { perform(actions[id]); return; }
  try {
    if (!["closePreview", "showShortcuts"].includes(id)) flushEdits();
    if (button.dataset.align) alignSelection(button.dataset.align); else actions[id]();
    syncChrome();
    if (state.tab === "visual" && !$("dialog[open]")) $(".stage-wrap").focus({ preventScroll: true });
  } catch (error) { status(error.message, true); }
});

function isTextInput(target) {
  return !!target?.closest("input, textarea, select, [contenteditable]:not([contenteditable=false])");
}

document.addEventListener("keydown", event => {
  if (event.isComposing || event.keyCode === 229 || event.defaultPrevented || $("dialog[open]")) return;
  const key = event.key.toLowerCase(), mod = event.ctrlKey || event.metaKey;
  if (mod && key === "s") { event.preventDefault(); if (!event.repeat) perform(save); return; }
  if (state.busy || isTextInput(event.target)) return;
  if (key === "?" && !mod) { event.preventDefault(); $("#shortcutsDialog").showModal(); return; }
  if (state.tab !== "visual" || !state.bundle) return;
  if (event.code === "Space" && !mod) {
    if (event.target.closest("button, a, summary")) return;
    event.preventDefault();
    state.space = true;
    $(".stage-wrap").classList.add("pan-ready");
    return;
  }
  if (key === "escape") {
    event.preventDefault();
    if (state.gesture) finishGesture(true);
    else {
      try { flushEdits(); selectLayer(null); } catch (error) { status(error.message, true); }
    }
    return;
  }
  if (state.gesture) return;
  let action;
  if (mod && key === "z") action = () => undo(event.shiftKey);
  else if (mod && key === "y") action = () => undo(true);
  else if (mod && key === "d") action = duplicateSelection;
  else if (mod && key === "a") action = () => { state.selection = flatten().map(item => item.path); renderVisual(); };
  else if (mod && key === "]") action = () => reorderSelection(1);
  else if (mod && key === "[") action = () => reorderSelection(-1);
  else if (mod && key === "0") action = actions.zoomFit;
  else if (mod && ["+", "="].includes(key)) action = actions.zoomIn;
  else if (mod && key === "-") action = actions.zoomOut;
  else if (!mod && !event.altKey && ["delete", "backspace"].includes(key)) action = deleteSelection;
  else if (!mod && !event.altKey && ["arrowleft", "arrowright", "arrowup", "arrowdown"].includes(key) && state.selection.length) {
    const step = event.shiftKey ? 10 : 1;
    action = () => nudge(key === "arrowleft" ? -step : key === "arrowright" ? step : 0, key === "arrowup" ? -step : key === "arrowdown" ? step : 0);
  }
  if (!action) return;
  event.preventDefault();
  if (event.repeat && !key.startsWith("arrow")) return;
  try { flushEdits(); action(); } catch (error) { status(error.message, true); }
});

document.addEventListener("keyup", event => {
  if (event.code !== "Space") return;
  state.space = false;
  $(".stage-wrap").classList.remove("pan-ready");
});
window.addEventListener("blur", () => {
  state.space = false;
  $(".stage-wrap").classList.remove("pan-ready");
  finishGesture(true);
});
window.addEventListener("beforeunload", event => {
  if (hasChanges()) { event.preventDefault(); event.returnValue = ""; }
});

$(".stage-wrap").addEventListener("pointerdown", startGesture);
$(".stage-wrap").addEventListener("pointermove", moveGesture);
$(".stage-wrap").addEventListener("pointerup", event => {
  if (state.gesture?.pointerId === event.pointerId) finishGesture();
});
$(".stage-wrap").addEventListener("pointercancel", () => finishGesture(true));
$(".stage-wrap").addEventListener("lostpointercapture", () => finishGesture(true));
$(".stage-wrap").addEventListener("wheel", event => {
  if (!(event.ctrlKey || event.metaKey) || state.busy || !state.bundle) return;
  event.preventDefault();
  if (state.gesture) return;
  const delta = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? 300 : 1);
  zoomTo(state.zoom * Math.exp(-delta * .002), { x: event.clientX, y: event.clientY });
}, { passive: false });

$("#templateSearch").addEventListener("input", renderTemplates);
$("#layerSearch").addEventListener("input", renderLayerList);
$("#layerJson").addEventListener("input", () => { state.jsonDirty = true; syncChrome(); });
for (const id of ["manifestSource", "sceneSource"]) {
  $("#" + id).addEventListener("input", () => {
    state.sourceDirty = $("#manifestSource").value !== state.sources?.manifest || $("#sceneSource").value !== state.sources?.scene;
    syncChrome();
  });
}
$("#asset").addEventListener("change", () => {
  const file = $("#asset").files[0];
  if (file) $("#assetName").value = "images/" + file.name;
});
$("#newTemplateForm").addEventListener("submit", event => { event.preventDefault(); perform(createTemplate); });
$("#versions").addEventListener("change", syncChrome);
$(".tab-switcher").addEventListener("keydown", event => {
  if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
  const buttons = $$("button[data-tab]");
  const current = buttons.indexOf(event.target);
  if (current < 0) return;
  event.preventDefault();
  event.stopPropagation();
  const index = event.key === "Home" ? 0 : event.key === "End" ? buttons.length - 1 : (current + (event.key === "ArrowRight" ? 1 : -1) + buttons.length) % buttons.length;
  perform(async () => { await switchTab(buttons[index].dataset.tab); buttons[index].focus(); });
});

for (const [type, [label]] of Object.entries(layerTypes)) $("#newLayerType").add(new Option(label, type));
for (const button of $$("button[data-tab]")) button.tabIndex = button.dataset.tab === state.tab ? 0 : -1;
new ResizeObserver(() => {
  if (state.tab === "visual" && state.fit && !state.gesture) renderStage();
}).observe($(".stage-wrap"));
renderVisual();
perform(async () => {
  await refreshTemplates();
  if (state.templates.length) await openTemplate(state.templates[0].id);
  else status("创建一个模板，开始设计你的状态卡");
});

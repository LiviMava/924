'use strict';

// This UI deliberately renders all model/document content as text, never HTML.
(() => {
  const $ = id => document.getElementById(id);
  const API = '/api/v1';
  const PAGE_SIZE = 100;
  const HISTORY_LIMIT = 20;
  const TOKEN_KEY = 'zhixu.rag.accessToken';
  const ACTIVE_STATES = new Set(['QUEUED', 'PROCESSING', 'DELETING']);
  const STATUS_LABELS = { QUEUED: '等待处理', PROCESSING: '解析索引中', READY: '已就绪', FAILED: '处理失败', DELETING: '删除中' };
  const state = {
    system: null, documents: [], offset: 0, history: [], token: '',
    chatController: null, chatGeneration: 0, uploadBusy: false,
    documentsLoading: false, pollTimer: null, pollFailures: 0, searchBusy: false
  };
  try { state.token = sessionStorage.getItem(TOKEN_KEY) || ''; } catch (_) { /* Private modes may deny storage. */ }
  const welcome = $('welcome');
  const initialEvidence = $('evidence-content').firstElementChild.cloneNode(true);

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }
  function icon(name) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', '#i-' + name);
    svg.setAttribute('aria-hidden', 'true');
    svg.append(use);
    return svg;
  }
  function button(label, className, handler) {
    const node = element('button', className, label);
    node.type = 'button';
    node.addEventListener('click', handler);
    return node;
  }
  function notice(message = '', type = '') {
    $('notice').textContent = message;
    $('notice').className = 'notice' + (type ? ' ' + type : '');
    $('notice').hidden = !message;
  }
  function errorText(error) {
    if (error.name === 'AbortError') return '请求已取消或超时，请稍后重试。';
    if (error instanceof TypeError) return '无法连接服务，请检查服务是否启动以及网络状态。';
    return error.message || '操作失败，请稍后重试。';
  }
  async function assertResponse(response) {
    if (response.ok) return response;
    let detail = '';
    try {
      const body = await response.json();
      detail = body.detail || body.message || body.title || '';
    } catch (_) { /* Do not render an arbitrary proxy error page. */ }
    if (response.status === 401 || response.status === 403) {
      detail = '访问未获授权，请在「连接设置」中检查 API 访问令牌。' + (detail ? ' ' + detail : '');
    }
    const error = new Error(detail || '请求失败（HTTP ' + response.status + '）');
    error.status = response.status;
    throw error;
  }
  function authHeaders(anonymous = false) {
    return !anonymous && state.token ? { Authorization: 'Bearer ' + state.token } : {};
  }
  async function api(path, options = {}) {
    const { anonymous = false, timeout = 30000, ...request } = options;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    try {
      const response = await fetch(API + path, {
        ...request, headers: { ...authHeaders(anonymous), ...request.headers }, signal: controller.signal
      });
      await assertResponse(response);
      return response.status === 204 ? null : await response.json();
    } finally { clearTimeout(timer); }
  }
  function jsonRequest(body) {
    return { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
  }
  function boundedHistory(messages) {
    const result = messages.slice(-HISTORY_LIMIT).map(message => ({
      role: message.role, content: String(message.content).slice(0, 8000)
    }));
    while (result.reduce((sum, message) => sum + message.content.length, 0) > 24000) {
      result.splice(0, 2);
    }
    return result;
  }
  function authenticated() {
    return !!state.system && (!state.system.authenticationRequired || !!state.token);
  }
  function updateReadiness() {
    const system = state.system;
    let description = '服务尚未连接，请刷新状态。';
    if (system) {
      if (!authenticated()) description = '需要访问令牌，请打开左下角「连接设置」。';
      else if (!system.chatConfigured) description = '问答模型未配置；可先上传文档并使用「检索调试」。';
      else if (!system.embeddingConfigured || !system.vectorHealthy) description = '关键词检索模式 · 配置向量服务可启用混合检索';
      else description = '结合知识库作答 · 最近 20 条消息作为上下文';
      if ($('nx-mode').checked) description = system.mcpConfigured ? 'NX 工具模式 · 修改前展示计划并确认' : 'NX MCP 未启用，请按项目 NX 接入说明配置';
    }
    $('chat-readiness').textContent = description;
    $('send-chat').disabled = !system?.chatConfigured || !authenticated() || !!state.chatController || !$('question').value.trim() || ($('nx-mode').checked && !system?.mcpConfigured);
    $('stop-chat').hidden = !state.chatController;
    $('search-submit').disabled = !authenticated() || state.searchBusy;
    $('file-input').disabled = !authenticated() || state.uploadBusy;
    $('history-count').textContent = '上下文 ' + state.history.length + ' / ' + HISTORY_LIMIT;
  }
  function renderSystem() {
    const system = state.system;
    const ready = system && system.chatConfigured && system.embeddingConfigured && system.vectorHealthy;
    $('connection-status').className = 'connection-status ' + (!system ? 'error' : ready ? 'ready' : 'warning');
    $('connection-label').textContent = !system ? '服务不可用' : ready ? '服务已就绪' : '关键词检索可用';
    const statuses = [
      ['问答模型', system?.chatConfigured, '已配置', '未配置'],
      ['NX MCP', system?.mcpConfigured, '已启用 · 需在 NX 内启用桥接', '未启用'],
      ['Embedding 模型', system?.embeddingConfigured, '已配置', '未配置 · 可用关键词检索'],
      ['向量数据库', system?.vectorHealthy, '连接正常', '未连接 · 可用关键词检索'],
      ['接口鉴权', system && (!system.authenticationRequired || !!state.token),
        system?.authenticationRequired ? '需要令牌 · 当前已填写' : '未启用', '需要令牌']
    ];
    $('service-statuses').replaceChildren(...statuses.map(([label, enabled, yes, no]) => {
      const row = element('div', 'service-row');
      row.append(element('span', '', label), element('span', 'service-value' + (enabled ? ' ready' : ''), system ? (enabled ? yes : no) : '状态未知'));
      return row;
    }));
    if (system?.maxUploadBytes) {
      $('upload-formats').textContent = '支持 PDF、Word、TXT、Markdown · 单文件最大 ' +
        Math.round(system.maxUploadBytes / 1024 / 1024) + ' MB · 暂不支持图片';
    }
    updateReadiness();
  }
  async function refreshSystem(showMessage = false) {
    $('refresh-system').disabled = true;
    try {
      state.system = await api('/system', { anonymous: true });
      renderSystem();
      if (showMessage) notice('服务状态已更新。', 'success');
    } catch (error) {
      state.system = null;
      renderSystem();
      notice(errorText(error), 'error');
    } finally { $('refresh-system').disabled = false; }
  }
  function setView(view) {
    document.querySelectorAll('.view').forEach(node => { node.hidden = node.id !== 'view-' + view; });
    document.querySelectorAll('[data-view]').forEach(node => {
      const active = node.dataset.view === view;
      node.classList.toggle('active', active);
      if (active) node.setAttribute('aria-current', 'page');
      else node.removeAttribute('aria-current');
    });
    $('page-name').textContent = { chat: '知识问答', documents: '文档管理', search: '检索调试' }[view];
  }
  function formatDate(value) {
    if (!value) return '—';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '—' : new Intl.DateTimeFormat('zh-CN', {
      month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
    }).format(date);
  }
  function indexLabel(mode) {
    return mode === 'HYBRID' ? '混合索引' : mode === 'LEXICAL' ? '关键词索引' : '索引待建立';
  }
  function modeLabel(mode) {
    if (!mode) return '检索模式由服务端决定';
    const value = String(mode).toUpperCase();
    if (value.includes('HYBRID')) return '混合检索 · ' + mode;
    if (value.includes('LEXICAL') || value.includes('BM25')) return '关键词检索 · ' + mode;
    return String(mode);
  }
  function renderDocuments() {
    const rows = state.documents.map(doc => {
      const row = element('tr');
      const nameCell = element('td');
      const name = element('div', 'document-name');
      const detail = element('div');
      detail.append(element('strong', '', doc.filename), element('small', '', indexLabel(doc.indexMode)));
      if (doc.error) detail.append(element('p', 'document-error', doc.error));
      name.append(icon('file'), detail);
      nameCell.append(name);
      const statusCell = element('td');
      const knownStatus = Object.hasOwn(STATUS_LABELS, doc.status) ? doc.status : '';
      statusCell.append(element('span', 'document-state ' + knownStatus.toLowerCase(), STATUS_LABELS[doc.status] || '状态未知'));
      const actionsCell = element('td');
      const actions = element('div', 'document-actions');
      if (doc.status !== 'DELETING') {
        const download = button('原文', '', () => downloadDocument(doc));
        download.setAttribute('aria-label', '下载 ' + doc.filename);
        actions.append(download);
      }
      if (doc.status === 'FAILED' || (doc.status === 'READY' && doc.indexMode === 'LEXICAL' && state.system?.embeddingConfigured && state.system?.vectorHealthy)) {
        actions.append(button(doc.status === 'FAILED' ? '重试' : '重建向量', '', () => retryDocument(doc)));
      }
      if (doc.status !== 'DELETING') {
        const remove = button('删除', 'delete-action', () => deleteDocument(doc));
        remove.setAttribute('aria-label', '删除 ' + doc.filename);
        actions.append(remove);
      } else actions.append(element('span', 'small-muted', '等待清理完成'));
      actionsCell.append(actions);
      row.append(nameCell, statusCell, element('td', '', doc.chunkCount ?? 0),
        element('td', '', formatDate(doc.updatedAt || doc.createdAt)), actionsCell);
      return row;
    });
    $('document-rows').replaceChildren(...rows);
    $('documents-empty').hidden = !!state.documents.length;
    $('document-summary').textContent = '本页 ' + state.documents.length + ' 份 · 每页 ' + PAGE_SIZE + ' 份';
    $('nav-doc-count').textContent = state.documents.length + (state.documents.length === PAGE_SIZE || state.offset > 0 ? '+' : '');
    const ready = state.documents.filter(doc => doc.status === 'READY');
    $('ready-count').replaceChildren(document.createTextNode(String(ready.length)), element('small', '', '本页可用文档'));
    $('chunk-count').replaceChildren(document.createTextNode(String(ready.reduce((sum, doc) => sum + Number(doc.chunkCount || 0), 0))), element('small', '', '本页知识片段'));
    $('knowledge-tip').textContent = ready.length ? '检索覆盖整个知识库，以上为当前列表页统计。' : '先添加文档，建立团队的知识底座。';
    $('documents-page').textContent = '第 ' + (state.offset / PAGE_SIZE + 1) + ' 页';
    $('documents-prev').disabled = state.offset === 0 || state.documentsLoading;
    $('documents-next').disabled = state.documents.length < PAGE_SIZE || state.documentsLoading;
  }
  function schedulePoll() {
    clearTimeout(state.pollTimer);
    state.pollTimer = null;
    if (state.pollFailures < 3 && authenticated() && state.documents.some(doc => ACTIVE_STATES.has(doc.status))) {
      state.pollTimer = setTimeout(() => refreshDocuments(false), 2500);
    }
  }
  async function refreshDocuments(manual = true) {
    if (state.documentsLoading) return;
    clearTimeout(state.pollTimer);
    if (manual) state.pollFailures = 0;
    state.documentsLoading = true;
    $('refresh-documents').disabled = true;
    try {
      const docs = await api('/documents?limit=' + PAGE_SIZE + '&offset=' + state.offset);
      if (!Array.isArray(docs)) throw new Error('文档列表返回格式异常。');
      state.documents = docs;
      state.pollFailures = 0;
      renderDocuments();
    } catch (error) {
      state.pollFailures += 1;
      if (error.status === 401 || error.status === 403) state.pollFailures = 3;
      $('document-summary').textContent = '列表暂不可用';
      if (manual || state.pollFailures >= 3) {
        notice(errorText(error) + (state.pollFailures >= 3 ? ' 自动刷新已暂停，可点击「刷新列表」重试。' : ''), 'error');
      }
    } finally {
      state.documentsLoading = false;
      $('refresh-documents').disabled = false;
      $('documents-prev').disabled = state.offset === 0;
      $('documents-next').disabled = state.documents.length < PAGE_SIZE;
      schedulePoll();
    }
  }
  async function uploadFiles(files) {
    if (state.uploadBusy || !files.length) return;
    if (!authenticated()) { notice('请先连接服务并在需要时填写访问令牌。', 'error'); return; }
    state.uploadBusy = true;
    updateReadiness();
    setView('documents');
    notice();
    let uploaded = 0;
    const failures = [];
    const formats = new Set(['pdf', 'doc', 'docx', 'txt', 'md']);
    for (const [index, file] of Array.from(files).entries()) {
      $('upload-status').textContent = '正在上传 ' + (index + 1) + ' / ' + files.length + '：' + file.name;
      const extension = file.name.split('.').pop().toLowerCase();
      if (!formats.has(extension)) { failures.push(file.name + '：不支持此文件格式'); continue; }
      if (state.system?.maxUploadBytes && file.size > state.system.maxUploadBytes) {
        failures.push(file.name + '：文件超过大小限制'); continue;
      }
      try {
        const body = new FormData();
        body.append('file', file);
        await api('/documents', { method: 'POST', body, timeout: 120000 });
        uploaded += 1;
      } catch (error) { failures.push(file.name + '：' + errorText(error)); }
    }
    state.uploadBusy = false;
    $('file-input').value = '';
    $('upload-status').textContent = '已提交 ' + uploaded + ' 份文档，解析和索引将在后台完成。' + (failures.length ? ' ' + failures.length + ' 份未上传。' : '');
    if (failures.length) notice(failures.join('；'), 'error');
    state.offset = 0;
    updateReadiness();
    await refreshDocuments();
  }
  async function retryDocument(doc) {
    try {
      const updated = await api('/documents/' + encodeURIComponent(doc.id) + '/retry', { method: 'POST' });
      Object.assign(doc, updated || { status: 'QUEUED', error: null });
      renderDocuments();
      schedulePoll();
      notice('已重新提交「' + doc.filename + '」。', 'success');
    } catch (error) { notice(errorText(error), 'error'); }
  }
  async function deleteDocument(doc) {
    if (!window.confirm('删除「' + doc.filename + '」及其检索索引？此操作不可恢复。')) return;
    try {
      await api('/documents/' + encodeURIComponent(doc.id), { method: 'DELETE' });
      // A 204 acknowledges the deletion request; the worker removes the document asynchronously.
      doc.status = 'DELETING';
      renderDocuments();
      schedulePoll();
      notice('已提交删除请求，后台清理完成后文档将从列表移除。', 'success');
    } catch (error) { notice(errorText(error), 'error'); }
  }
  async function downloadDocument(doc) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 60000);
    try {
      const response = await fetch(API + '/documents/' + encodeURIComponent(doc.documentId || doc.id) + '/content', {
        headers: authHeaders(), signal: controller.signal
      });
      await assertResponse(response);
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = element('a');
      link.href = url;
      link.download = doc.filename || 'document';
      document.body.append(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) { notice(errorText(error), 'error'); }
    finally { clearTimeout(timer); }
  }
  function citationLocation(hit) {
    const location = [];
    if (hit.page !== undefined && hit.page !== null && Number(hit.page) > 0) location.push('第 ' + hit.page + ' 页');
    if (hit.section) location.push(String(hit.section));
    return location.join(' · ') || '原文片段';
  }
  function citationHeading(hit, index) {
    const title = element('div', 'citation-title');
    title.append(element('span', 'citation-number', '[' + (index + 1) + ']'), element('span', '', hit.filename || '未命名文档'));
    return title;
  }
  function renderEvidence(citations = [], mode, warnings = []) {
    $('citation-count').textContent = String(citations.length);
    const nodes = [];
    if (mode) nodes.push(element('div', 'retrieval-mode', modeLabel(mode)));
    for (const [index, hit] of citations.entries()) {
      const card = element('article', 'citation-card');
      card.append(citationHeading(hit, index), element('div', 'citation-location', citationLocation(hit)),
        element('p', '', hit.text || ''), button('下载原文 ↗', 'text-button', () => downloadDocument(hit)));
      nodes.push(card);
    }
    if (!citations.length) nodes.push(element('p', 'empty-evidence', '尚未找到足够的文档依据。可补充资料，或调整问题中的关键词。'));
    if (warnings.length) nodes.push(element('p', 'empty-evidence', warnings.join('\n')));
    $('evidence-content').replaceChildren(...nodes);
  }
  function addMessage(role, text) {
    welcome.hidden = true;
    const node = element('article', 'message ' + role);
    const heading = element('div', 'message-heading');
    heading.append(element('span', 'message-avatar', role === 'user' ? '我' : '知'), element('span', '', role === 'user' ? '你' : '知识助手'));
    const content = element('div', 'message-text', text);
    node.append(heading, content);
    $('messages').append(node);
    scrollMessages();
    return { node, content };
  }
  function scrollMessages() { $('messages').scrollTop = $('messages').scrollHeight; }
  function showAnswerMetadata(message, data) {
    const citations = Array.isArray(data.citations) ? data.citations : [];
    const warnings = Array.isArray(data.warnings) ? data.warnings : [];
    const meta = element('div', 'message-meta');
    meta.append(element('span', '', data.toolTrace ? 'NX 工具模式 · 以执行回执为准' : data.grounded ? '已结合文档依据' : '依据不足，请核实'));
    if (citations.length) meta.append(button('查看 ' + citations.length + ' 条引用 ↗', '', () => {
      renderEvidence(citations, data.retrievalMode, warnings);
      if (window.matchMedia('(max-width: 720px)').matches) $('evidence-title').scrollIntoView({ block: 'start', behavior: 'smooth' });
    }));
    message.node.append(meta);
    if (warnings.length) message.node.append(element('p', 'message-warning', warnings.join('\n')));
    renderEvidence(citations, data.retrievalMode, warnings);
  }
  function renderToolAction(message, action) {
    if (message.node.querySelector('[data-action-id="' + action.id + '"]')) return;
    const card = element('section', 'nx-action');
    card.dataset.actionId = action.id;
    const names = { PENDING: '等待确认', EXECUTING: '执行中', SUCCEEDED: '执行成功', FAILED: '执行失败', UNKNOWN: '结果未知，请查询并检查 NX', CANCELLED: '已取消', EXPIRED: '计划已过期，请重新预览' };
    card.append(element('strong', '', '待确认：在 NX 中新建独立长方体'));
    const p = action.preview.parameters || {};
    const context = action.preview.context || {};
    card.append(element('p', '', '目标零件：' + (context.partName || '未知') + ' · 单位：' + (context.units || '未知') +
      (context.backend === 'MOCK' ? ' · 模拟模式，未连接真实 NX' : ' · NX ' + (context.nxVersion || ''))));
    card.append(element('p', '', '长度 X：' + p.length + ' mm　宽度 Y：' + p.width + ' mm　高度 Z：' + p.height + ' mm'));
    card.append(element('p', '', '绝对原点：(' + p.x + ', ' + p.y + ', ' + p.z + ') mm'));
    card.append(element('p', 'small-muted', '新建独立实体，不合并现有实体。不会自动保存零件；成功后可在 NX 中撤销。'));
    const preview = element('pre', 'nx-preview', JSON.stringify(action.preview, null, 2));
    const details = element('details', 'nx-details');
    details.append(element('summary', '', '接口与执行详情'), preview);
    const status = element('p', '', '状态：' + (names[action.status] || action.status) + ' · 有效至 ' + new Date(action.expiresAt).toLocaleTimeString());
    const controls = element('div', 'nx-controls');
    async function update(operation) {
      for (const child of controls.children) child.disabled = true;
      status.textContent = '正在处理，请勿重复提交…';
      try {
        action = await api('/agent/actions/' + encodeURIComponent(action.id) + '/' + operation,
          { ...jsonRequest(operation === 'approve' ? { argumentsHash: action.argumentsHash } : {}), timeout: 45000 });
        status.textContent = '状态：' + (names[action.status] || action.status) + (action.result?.backend === 'MOCK' ? '（模拟，未修改 NX）' : '') +
          (action.result?.message ? ' · ' + action.result.message : '');
        preview.textContent = JSON.stringify({ preview: action.preview, result: action.result, commandId: action.commandId }, null, 2);
      } catch (error) {
        status.textContent = errorText(error) + ' 执行结果可能未知，使用查询结果核查；请勿重复建模。';
        // Refresh durable state. A network timeout must never be treated as a safe retry.
        try { action = await api('/agent/actions/' + encodeURIComponent(action.id)); } catch (_) { action.status = 'UNKNOWN'; }
      } finally {
        confirm.disabled = action.status !== 'PENDING';
        cancel.disabled = action.status !== 'PENDING';
        reconcile.disabled = !['UNKNOWN', 'EXECUTING'].includes(action.status);
      }
    }
    const confirm = button('确认并执行', 'button button-primary', () => update('approve'));
    const cancel = button('取消计划', 'button', () => update('cancel'));
    const reconcile = button('查询执行结果', 'button', () => update('reconcile'));
    reconcile.disabled = true;
    controls.append(confirm, cancel, reconcile); card.append(status, controls, details); message.node.append(card);
  }
  // Parse SSE frames across arbitrary network chunk boundaries, including CRLF.
  async function readEventStream(response, handleEvent, signal) {
    if (!response.body) throw new Error('当前浏览器无法读取流式响应。');
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let completed = false;
    function dispatch(frame) {
      let event = 'message';
      const data = [];
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (!data.length) return;
      let payload;
      try { payload = JSON.parse(data.join('\n')); }
      catch (_) { throw new Error('服务返回了无法解析的流式消息，请重试。'); }
      handleEvent(event, payload);
      if (event === 'done') completed = true;
    }
    try {
      while (!completed) {
        if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
        const { value, done } = await reader.read();
        buffer += decoder.decode(value, { stream: !done });
        buffer = buffer.replace(/\r\n/g, '\n');
        let boundary;
        while ((boundary = buffer.indexOf('\n\n')) !== -1) {
          const frame = buffer.slice(0, boundary);
          buffer = buffer.slice(boundary + 2);
          dispatch(frame);
          if (completed) break;
        }
        if (done) {
          if (!completed && buffer.trim()) dispatch(buffer);
          break;
        }
      }
      if (!completed) throw new Error('连接在回答完成前中断，请重试。');
    } finally {
      try { await reader.cancel(); } catch (_) { /* The connection may already be closed. */ }
      reader.releaseLock();
    }
  }
  async function sendQuestion(event) {
    event.preventDefault();
    const question = $('question').value.trim();
    if (!question || state.chatController) return;
    if (!authenticated() || !state.system?.chatConfigured) { notice('请先在服务端配置问答模型，并检查连接设置。', 'error'); return; }
    notice();
    const generation = ++state.chatGeneration;
    const controller = new AbortController();
    state.chatController = controller;
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, 180000);
    const history = boundedHistory(state.history.slice(-(HISTORY_LIMIT - 2)));
    $('question').value = '';
    addMessage('user', question);
    const answer = addMessage('assistant', '正在检索文档依据…');
    let accumulated = '';
    let result = null;
    const toolNodes = new Map();
    $('citation-count').textContent = '0';
    $('evidence-content').replaceChildren(element('p', 'empty-evidence', '正在查找与问题相关的文档片段…'));
    updateReadiness();
    try {
      const response = await fetch(API + ($('nx-mode').checked ? '/agent/chat/stream' : '/chat/stream'), {
        ...jsonRequest({ question, history }),
        headers: { ...authHeaders(), 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        signal: controller.signal
      });
      await assertResponse(response);
      await readEventStream(response, (eventName, data) => {
        if (generation !== state.chatGeneration) return;
        if (eventName === 'sources') {
          renderEvidence(data.citations || [], data.retrievalMode, data.warnings || []);
          answer.content.textContent = '正在根据文档组织答案…';
        } else if (eventName === 'delta') {
          accumulated += data.text || '';
          answer.content.textContent = accumulated;
          scrollMessages();
        } else if (eventName === 'tool') {
          if (!toolNodes.has(data.name)) {
            const node = element('p', 'nx-tool-event'); toolNodes.set(data.name, node); answer.node.append(node);
          }
          const label = data.name === 'nx_get_context' ? '读取 NX 工作零件' : '生成长方体预览';
          let text = label + ' · ' + (data.status === 'RUNNING' ? '处理中…' : '已完成');
          if (data.name === 'nx_get_context' && data.result) text += ' · ' + (data.result.partName || '无工作零件') + ' · ' + (data.result.units || '');
          toolNodes.get(data.name).textContent = text;
        } else if (eventName === 'approval') {
          renderToolAction(answer, data);
        } else if (eventName === 'done') {
          result = data;
          // The server's final response is authoritative, even after partial deltas.
          answer.content.textContent = data.answer || '未生成可用答案。';
        } else if (eventName === 'error') {
          throw new Error(data.message || data.detail || '生成失败，请稍后重试。');
        }
      }, controller.signal);
      if (generation !== state.chatGeneration) return;
      if (!result) throw new Error('服务未返回完整的回答，请重试。');
      state.history = boundedHistory([...history, { role: 'user', content: question }, { role: 'assistant', content: result.answer || '' }]);
      showAnswerMetadata(answer, result);
      if (result.pendingAction) renderToolAction(answer, result.pendingAction);
    } catch (error) {
      if (generation !== state.chatGeneration) return;
      const reason = error.name === 'AbortError'
        ? (timedOut ? '生成超时，请稍后重试。' : '已停止生成。此条未完成的回答不计入对话上下文。')
        : errorText(error);
      answer.node.classList.add('error');
      answer.content.textContent = accumulated ? accumulated + '\n\n' + reason : reason;
    } finally {
      clearTimeout(timer);
      if (generation === state.chatGeneration) {
        state.chatController = null;
        updateReadiness();
        scrollMessages();
        $('question').focus();
      }
    }
  }
  function newChat() {
    state.chatGeneration += 1;
    state.chatController?.abort();
    state.chatController = null;
    state.history = [];
    $('messages').replaceChildren(welcome);
    welcome.hidden = false;
    $('evidence-content').replaceChildren(initialEvidence.cloneNode(true));
    $('citation-count').textContent = '0';
    $('question').value = '';
    notice();
    updateReadiness();
    $('question').focus();
  }
  async function searchDocuments(event) {
    event.preventDefault();
    const query = $('search-query').value.trim();
    if (!query || state.searchBusy) return;
    if (!authenticated()) { notice('请先检查服务连接与访问令牌。', 'error'); return; }
    state.searchBusy = true;
    updateReadiness();
    notice();
    $('search-summary').textContent = '正在检索相关文档片段…';
    $('search-results').replaceChildren();
    try {
      const response = await api('/search', jsonRequest({ query, topK: Number($('search-topk').value) }));
      const hits = response.hits || [];
      $('search-summary').textContent = '找到 ' + hits.length + ' 个相关片段 · ' + modeLabel(response.retrievalMode) +
        (response.warnings?.length ? '\n' + response.warnings.join('\n') : '');
      $('search-results').replaceChildren(...hits.map((hit, index) => {
        const card = element('article', 'search-hit');
        const footer = element('div', 'search-hit-footer');
        footer.append(element('span', 'search-score', Number.isFinite(hit.score) ? '相关性分数 ' + hit.score.toFixed(4) : ''),
          button('下载原文 ↗', 'text-button', () => downloadDocument(hit)));
        card.append(citationHeading(hit, index), element('div', 'citation-location', citationLocation(hit)),
          element('p', '', hit.text || ''), footer);
        return card;
      }));
      if (!hits.length) {
        const empty = element('div', 'search-empty');
        empty.append(icon('search'), element('h2', '', '没有找到相关片段'), element('p', '', '尝试更明确的关键词，或检查文档是否已完成索引。'));
        $('search-results').append(empty);
      }
    } catch (error) {
      $('search-summary').textContent = '检索未完成。';
      notice(errorText(error), 'error');
    } finally { state.searchBusy = false; updateReadiness(); }
  }
  function saveToken(value) {
    state.token = value.trim();
    try {
      if (state.token) sessionStorage.setItem(TOKEN_KEY, state.token);
      else sessionStorage.removeItem(TOKEN_KEY);
    } catch (_) { notice('浏览器禁用了会话存储，令牌仅在当前页面内有效。'); }
  }
  function setupPagination() {
    const pagination = element('div', 'pagination');
    const previous = button('上一页', 'button button-small', () => { if (!state.documentsLoading) { state.offset = Math.max(0, state.offset - PAGE_SIZE); refreshDocuments(); } });
    previous.id = 'documents-prev';
    const page = element('span', '', '第 1 页');
    page.id = 'documents-page';
    const next = button('下一页', 'button button-small', () => { if (!state.documentsLoading) { state.offset += PAGE_SIZE; refreshDocuments(); } });
    next.id = 'documents-next';
    previous.disabled = true;
    next.disabled = true;
    pagination.append(previous, page, next);
    document.querySelector('.document-card').append(pagination);
  }
  document.querySelectorAll('[data-view]').forEach(node => node.addEventListener('click', () => setView(node.dataset.view)));
  document.querySelectorAll('[data-open-documents]').forEach(node => node.addEventListener('click', () => setView('documents')));
  document.querySelectorAll('[data-prompt]').forEach(node => node.addEventListener('click', () => {
    $('question').value = node.dataset.prompt;
    updateReadiness();
    $('question').focus();
  }));
  $('question').addEventListener('input', updateReadiness);
  $('nx-mode').addEventListener('change', () => { newChat(); updateReadiness(); });
  $('question').addEventListener('keydown', event => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
      event.preventDefault();
      if (!$('send-chat').disabled) $('chat-form').requestSubmit();
    }
  });
  $('chat-form').addEventListener('submit', sendQuestion);
  $('new-chat').addEventListener('click', newChat);
  $('stop-chat').addEventListener('click', () => state.chatController?.abort());
  $('search-form').addEventListener('submit', searchDocuments);
  $('refresh-system').addEventListener('click', async () => { await refreshSystem(true); await refreshDocuments(); });
  $('refresh-documents').addEventListener('click', () => refreshDocuments());
  $('file-input').addEventListener('change', event => uploadFiles(Array.from(event.target.files)));
  for (const name of ['dragenter', 'dragover']) $('upload-zone').addEventListener(name, event => {
    event.preventDefault();
    $('upload-zone').classList.add('dragging');
  });
  for (const name of ['dragleave', 'drop']) $('upload-zone').addEventListener(name, event => {
    event.preventDefault();
    $('upload-zone').classList.remove('dragging');
  });
  $('upload-zone').addEventListener('drop', event => uploadFiles(Array.from(event.dataTransfer.files)));
  $('settings-open').addEventListener('click', () => {
    $('access-token').value = state.token;
    renderSystem();
    $('settings-dialog').showModal();
  });
  $('settings-close').addEventListener('click', () => $('settings-dialog').close());
  $('settings-form').addEventListener('submit', async event => {
    event.preventDefault();
    saveToken($('access-token').value);
    $('settings-dialog').close();
    notice();
    await refreshSystem();
    await refreshDocuments();
  });
  $('clear-token').addEventListener('click', () => {
    saveToken('');
    $('access-token').value = '';
    renderSystem();
    clearTimeout(state.pollTimer);
  });
  window.addEventListener('pagehide', () => { clearTimeout(state.pollTimer); state.chatController?.abort(); });
  setupPagination();
  renderSystem();
  refreshSystem().then(() => { if (state.system) return refreshDocuments(); });
})();

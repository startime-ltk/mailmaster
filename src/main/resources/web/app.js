const $ = (s) => document.querySelector(s);
let state = {
  demoMode: false, accounts: [], messages: [],
  hasOlder: false, draftCount: 0, trashCount: 0, unread: 0,
  currentMsgId: null, currentDraftId: 0, loadingOlder: false
};

async function api(path, opts = {}) {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...opts
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
  return data;
}

function fmtTime(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  const diff = (Date.now() - ts) / 1000;
  if (diff < 60) return '刚刚';
  if (diff < 3600) return Math.floor(diff / 60) + ' 分钟前';
  if (diff < 86400) return Math.floor(diff / 3600) + ' 小时前';
  return d.toLocaleDateString() + ' ' + d.toLocaleTimeString().slice(0, 5);
}

function providerColor(p) {
  if (p.includes('QQ')) return '#f5a623';
  if (p.includes('163') || p.includes('网易')) return '#d4282d';
  if (p.includes('Outlook') || p.includes('Hotmail')) return '#0078d4';
  return '#666';
}

const CAT_STYLE = {
  '重要': { bg: '#fff1f0', fg: '#cf1322' },
  '推广': { bg: '#fff7e6', fg: '#d46b08' },
  '社交': { bg: '#e6f4ff', fg: '#0958d9' },
  '其他': { bg: '#f5f5f5', fg: '#8c8c8c' }
};
function catBadge(cat) {
  const s = CAT_STYLE[cat] || CAT_STYLE['其他'];
  return `<span class="cat-badge" style="background:${s.bg};color:${s.fg}">${escapeHtml(cat || '其他')}</span>`;
}

function escapeHtml(s) {
  return (s || '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function toast(msg) {
  const t = $('#toast');
  t.textContent = msg; t.hidden = false;
  setTimeout(() => t.hidden = true, 2200);
}

async function loadAll() {
  try {
    const [status, acc, box] = await Promise.all([
      api('/api/status'), api('/api/accounts'), api('/api/inbox')
    ]);
    state.demoMode = status.demoMode;
    state.accounts = acc.accounts || [];
    state.messages = box.messages || [];
    state.unread = status.unread || 0;
    state.trashCount = status.trashCount || 0;
    state.draftCount = status.draftCount || 0;
    state.hasOlder = status.hasOlder || false;
    render();
  } catch (e) {
    toast('加载失败: ' + e.message);
  }
}

function render() {
  $('#demoBadge').hidden = !state.demoMode;
  const ub = $('#unreadBadge');
  if (state.unread > 0) { ub.hidden = false; ub.textContent = state.unread + ' 未读'; }
  else ub.hidden = true;

  const acc = $('#accounts');
  if (state.accounts.length === 0) {
    acc.innerHTML = '<p class="empty">还没有账户，点右上角 ＋ 添加</p>';
  } else {
    acc.innerHTML = state.accounts.map(a =>
      `<div class="acc-chip"><span class="dot" style="background:${providerColor(a.provider)}"></span>${escapeHtml(a.displayName || a.email)}<small>${escapeHtml(a.provider)}</small></div>`
    ).join('');
  }

  const dc = $('#draftCount');
  if (state.draftCount > 0) { dc.hidden = false; dc.textContent = state.draftCount; } else dc.hidden = true;
  const tc = $('#trashCount');
  if (state.trashCount > 0) { tc.hidden = false; tc.textContent = state.trashCount; } else tc.hidden = true;

  $('#inboxCount').textContent = state.messages.length ? `（${state.messages.length} 封）` : '';
  const inbox = $('#inbox');
  if (state.messages.length === 0) {
    $('#empty').hidden = false;
    inbox.innerHTML = '';
  } else {
    $('#empty').hidden = true;
    inbox.innerHTML = state.messages.map(m =>
      `<div class="card" data-id="${encodeURIComponent(m.id)}">
        <div class="card-top">
          <span class="tag" style="background:${providerColor(m.accountEmail || '')}">${escapeHtml(m.accountEmail || '')}</span>
          ${catBadge(m.category)}
          ${m.read ? '' : '<span class="unread">●</span>'}
          <button class="card-del" data-del="${encodeURIComponent(m.id)}" title="移入回收站">🗑</button>
        </div>
        <div class="card-subject">${escapeHtml(m.subject)}</div>
        <div class="card-from">${escapeHtml(m.from)}</div>
        <div class="card-time">${fmtTime(m.sentDate)}</div>
      </div>`
    ).join('');
    inbox.querySelectorAll('.card').forEach(c =>
      c.addEventListener('click', () => openMessage(decodeURIComponent(c.dataset.id))));
    inbox.querySelectorAll('.card-del').forEach(b =>
      b.addEventListener('click', async (e) => {
        e.stopPropagation();
        const id = decodeURIComponent(b.dataset.del);
        try {
          await api('/api/messages/' + encodeURIComponent(id) + '/trash', { method: 'POST', body: '{}' });
          toast('已移入回收站');
          await loadAll();
        } catch (err) { toast('失败: ' + err.message); }
      }));
  }
  $('#olderBtn').hidden = !state.hasOlder;
}

async function openMessage(id) {
  try {
    const m = await api('/api/messages/' + encodeURIComponent(id));
    state.currentMsgId = id;
    $('#msgSubject').textContent = m.subject || '(无主题)';
    $('#msgMeta').innerHTML = `<b>发件人：</b>${escapeHtml(m.from)}<br><b>收件人：</b>${escapeHtml(m.to || '')}<br><b>时间：</b>${fmtTime(m.sentDate)}`;
    $('#msgBody').srcdoc = m.bodyHtml || escapeHtml(m.bodyText || '');
    $('#msgRetryHint').hidden = !!m.hasContent;
    showModal('#msgModal');
    if (!m.read) {
      await api('/api/messages/' + encodeURIComponent(id) + '/read', { method: 'POST', body: '{}' });
      state.unread = Math.max(0, state.unread - 1);
      render();
    }
  } catch (e) { toast(e.message); }
}

$('#msgRetryBtn').addEventListener('click', async () => {
  const id = state.currentMsgId;
  if (!id) return;
  $('#msgRetryBtn').disabled = true;
  $('#msgRetryBtn').textContent = '加载中…';
  try {
    const m = await api('/api/messages/' + encodeURIComponent(id) + '/retry', { method: 'POST', body: '{}' });
    $('#msgBody').srcdoc = m.bodyHtml || escapeHtml(m.bodyText || '');
    $('#msgRetryHint').hidden = !!m.hasContent;
    toast('正文已更新 ✅');
  } catch (e) { toast('失败: ' + e.message); }
  $('#msgRetryBtn').disabled = false;
  $('#msgRetryBtn').textContent = '重新加载正文';
});

$('#msgDeleteBtn').addEventListener('click', async () => {
  const id = state.currentMsgId;
  if (!id) return;
  try {
    await api('/api/messages/' + encodeURIComponent(id) + '/trash', { method: 'POST', body: '{}' });
    hideModal($('#msgModal'));
    toast('已移入回收站');
    await loadAll();
  } catch (e) { toast('失败: ' + e.message); }
});

function showModal(sel) { $(sel).hidden = false; }
function hideModal(el) { el.hidden = true; }
document.querySelectorAll('[data-close]').forEach(b => b.addEventListener('click', e => {
  const modal = e.target.closest('.modal');
  if (modal.id === 'composeModal') autoSaveDraft();
  hideModal(modal);
}));
document.querySelectorAll('.modal').forEach(m => m.addEventListener('click', e => {
  if (e.target === m) { if (m.id === 'composeModal') autoSaveDraft(); hideModal(m); }
}));

$('#refreshBtn').addEventListener('click', loadAll);
$('#olderBtn').addEventListener('click', async () => {
  if (state.loadingOlder) return;
  state.loadingOlder = true;
  $('#olderBtn').textContent = '加载中…';
  try {
    const r = await api('/api/older', { method: 'POST', body: '{}' });
    toast(r.loaded > 0 ? `已加载 ${r.loaded} 封更早的邮件` : '没有更早的邮件了');
    await loadAll();
  } catch (e) { toast('失败: ' + e.message); }
  state.loadingOlder = false;
  $('#olderBtn').textContent = '📜 加载更早的邮件';
});

$('#addBtn').addEventListener('click', () => {
  $('#addHint').textContent = providerHint($('#addProvider').value);
  showModal('#addModal');
});
$('#addProvider').addEventListener('change', e => $('#addHint').textContent = providerHint(e.target.value));
function providerHint(k) {
  if (k === 'QQ') return 'QQ：网页端 设置→账号与安全→开启 IMAP/SMTP→生成16位授权码（不是QQ密码）';
  if (k === 'NETEASE') return '163：设置→POP3/SMTP/IMAP→开启→生成授权码';
  if (k === 'OUTLOOK') return 'Outlook：需先开两步验证→生成应用密码，SMTP 用 587+STARTTLS';
  return '';
}
$('#addSubmit').addEventListener('click', async () => {
  const email = $('#addEmail').value.trim();
  const provider = $('#addProvider').value;
  const password = $('#addPassword').value;
  const name = $('#addName').value.trim();
  if (!email || !password) { toast('请填邮箱和授权码'); return; }
  try {
    await api('/api/accounts', { method: 'POST', body: JSON.stringify({ email, provider, password, displayName: name }) });
    hideModal($('#addModal'));
    $('#addEmail').value = $('#addPassword').value = $('#addName').value = '';
    toast('已添加 ✅');
    await loadAll();
  } catch (e) { toast('失败: ' + e.message); }
});

// ---------- 写邮件 / 草稿 ----------
function composeContent() {
  return {
    accountId: $('#composeFrom').value,
    to: $('#composeTo').value.trim(),
    subject: $('#composeSubject').value.trim(),
    body: $('#composeBody').value
  };
}
function composeHasContent() {
  const c = composeContent();
  return !!(c.to || c.subject || c.body);
}
function openCompose(draft) {
  state.currentDraftId = draft ? Number(draft.id) || 0 : 0;
  $('#composeFrom').innerHTML = state.accounts.map(a =>
    `<option value="${a.id}">${escapeHtml(a.displayName || a.email)}</option>`).join('');
  if (draft) {
    $('#composeFrom').value = draft.accountId || (state.accounts[0] && state.accounts[0].id) || '';
    $('#composeTo').value = draft.to || '';
    $('#composeSubject').value = draft.subject || '';
    $('#composeBody').value = draft.body || '';
  } else {
    $('#composeTo').value = $('#composeSubject').value = $('#composeBody').value = '';
  }
  showModal('#composeModal');
}
$('#composeBtn').addEventListener('click', () => openCompose(null));
$('#composeSubmit').addEventListener('click', async () => {
  const c = composeContent();
  if (!c.accountId || !c.to) { toast('请选择发件账户并填收件人'); return; }
  try {
    const r = await api('/api/send', { method: 'POST', body: JSON.stringify(c) });
    if (state.currentDraftId > 0) {
      await api('/api/drafts/' + state.currentDraftId, { method: 'DELETE' });
      state.currentDraftId = 0;
    }
    hideModal($('#composeModal'));
    if (r.demo) toast('演示模式：已模拟发送（未真正联网）');
    else toast('已发送 ✅');
    $('#composeTo').value = $('#composeSubject').value = $('#composeBody').value = '';
    await loadAll();
  } catch (e) { toast('失败: ' + e.message); }
});
$('#composeDraftBtn').addEventListener('click', async () => {
  const c = composeContent();
  if (!c.accountId) { toast('请先选择发件账户'); return; }
  if (!composeHasContent()) { toast('内容为空，无需保存'); return; }
  try {
    const r = await api('/api/drafts', {
      method: 'POST',
      body: JSON.stringify({ id: state.currentDraftId || undefined, ...c })
    });
    state.currentDraftId = r.id || 0;
    hideModal($('#composeModal'));
    toast('已存入草稿箱 ✅');
    await loadAll();
  } catch (e) { toast('失败: ' + e.message); }
});
/** 关闭写邮件窗口时有内容 → 静默自动存草稿（防丢） */
async function autoSaveDraft() {
  const c = composeContent();
  if (!c.accountId || !composeHasContent()) return;
  try {
    const r = await api('/api/drafts', {
      method: 'POST',
      body: JSON.stringify({ id: state.currentDraftId || undefined, ...c })
    });
    state.currentDraftId = r.id || 0;
  } catch (e) { /* 静默失败，不打扰 */ }
}

// ---------- 草稿箱 ----------
$('#draftsBtn').addEventListener('click', async () => {
  try {
    const r = await api('/api/drafts');
    const list = r.drafts || [];
    $('#draftEmpty').hidden = list.length > 0;
    $('#draftClearBtn').hidden = list.length === 0;
    $('#draftList').innerHTML = list.map(d =>
      `<div class="row-item" data-id="${d.id}">
        <div class="row-main">
          <div class="row-title">${escapeHtml(d.subject || '(无主题)')}</div>
          <div class="row-sub">致：${escapeHtml(d.to || '(未填收件人)')} · ${fmtTime(d.updatedAt)}</div>
        </div>
        <div class="row-actions">
          <button class="mini-btn" data-edit="${d.id}">续写</button>
          <button class="mini-btn danger" data-deldraft="${d.id}">删除</button>
        </div>
      </div>`
    ).join('');
    $('#draftList').querySelectorAll('[data-edit]').forEach(b =>
      b.addEventListener('click', async () => {
        const d = list.find(x => String(x.id) === b.dataset.edit);
        hideModal($('#draftsModal'));
        openCompose(d);
      }));
    $('#draftList').querySelectorAll('[data-deldraft]').forEach(b =>
      b.addEventListener('click', async () => {
        try {
          await api('/api/drafts/' + b.dataset.deldraft, { method: 'DELETE' });
          toast('草稿已删除');
          $('#draftsBtn').click();
          await loadAll();
        } catch (e) { toast('失败: ' + e.message); }
      }));
    showModal('#draftsModal');
  } catch (e) { toast('失败: ' + e.message); }
});
$('#draftClearBtn').addEventListener('click', async () => {
  try {
    const r = await api('/api/drafts');
    for (const d of (r.drafts || [])) {
      await api('/api/drafts/' + d.id, { method: 'DELETE' });
    }
    toast('草稿箱已清空');
    $('#draftsBtn').click();
    await loadAll();
  } catch (e) { toast('失败: ' + e.message); }
});

// ---------- 回收站 ----------
$('#trashBtn').addEventListener('click', async () => {
  try {
    const r = await api('/api/trash');
    const list = r.messages || [];
    $('#trashEmpty').hidden = list.length > 0;
    $('#trashClearBtn').hidden = list.length === 0;
    $('#trashList').innerHTML = list.map(m =>
      `<div class="row-item" data-id="${encodeURIComponent(m.id)}">
        <div class="row-main">
          <div class="row-title">${escapeHtml(m.subject || '(无主题)')}</div>
          <div class="row-sub">${escapeHtml(m.from)} · ${fmtTime(m.sentDate)}</div>
        </div>
        <div class="row-actions">
          <button class="mini-btn" data-restore="${encodeURIComponent(m.id)}">恢复</button>
          <button class="mini-btn danger" data-purge="${encodeURIComponent(m.id)}">彻底删除</button>
        </div>
      </div>`
    ).join('');
    $('#trashList').querySelectorAll('[data-restore]').forEach(b =>
      b.addEventListener('click', async () => {
        try {
          await api('/api/trash/restore', { method: 'POST', body: JSON.stringify({ id: decodeURIComponent(b.dataset.restore) }) });
          toast('已恢复');
          $('#trashBtn').click();
          await loadAll();
        } catch (e) { toast('失败: ' + e.message); }
      }));
    $('#trashList').querySelectorAll('[data-purge]').forEach(b =>
      b.addEventListener('click', async () => {
        const id = decodeURIComponent(b.dataset.purge);
        if (!confirm('彻底删除后不可恢复，确定？')) return;
        try {
          await api('/api/trash/' + encodeURIComponent(id), { method: 'DELETE' });
          toast('已彻底删除');
          $('#trashBtn').click();
          await loadAll();
        } catch (e) { toast('失败: ' + e.message); }
      }));
    showModal('#trashModal');
  } catch (e) { toast('失败: ' + e.message); }
});
$('#trashClearBtn').addEventListener('click', async () => {
  if (!confirm('清空回收站？所有邮件将不可恢复。')) return;
  try {
    const r = await api('/api/trash/purge-all', { method: 'POST', body: '{}' });
    toast(`已清空 ${r.purged || 0} 封`);
    $('#trashBtn').click();
    await loadAll();
  } catch (e) { toast('失败: ' + e.message); }
});

// ---------- AI 设置 ----------
$('#aiBtn').addEventListener('click', async () => {
  try {
    const s = await api('/api/ai');
    $('#aiEnabled').checked = !!s.enabled;
    $('#aiKey').value = '';
    $('#aiKey').placeholder = s.apiKeySet ? `已保存（${s.apiKey}），留空不修改` : 'sk-...';
    $('#aiBaseUrl').value = s.baseUrl || '';
    $('#aiModel').value = s.model || '';
    $('#aiTestResult').textContent = '';
    showModal('#aiModal');
  } catch (e) { toast('失败: ' + e.message); }
});
$('#aiTestBtn').addEventListener('click', async () => {
  const body = {
    enabled: true,
    apiKey: $('#aiKey').value.trim(),
    baseUrl: $('#aiBaseUrl').value.trim(),
    model: $('#aiModel').value.trim()
  };
  $('#aiTestBtn').disabled = true;
  $('#aiTestResult').textContent = '正在测试…';
  try {
    const r = await api('/api/ai/test', { method: 'POST', body: JSON.stringify(body) });
    $('#aiTestResult').textContent = '✅ 连接成功，AI 返回：' + (r.result || '(空)');
  } catch (e) {
    $('#aiTestResult').textContent = '❌ ' + e.message;
  }
  $('#aiTestBtn').disabled = false;
});
$('#aiSaveBtn').addEventListener('click', async () => {
  const body = {
    enabled: $('#aiEnabled').checked,
    apiKey: $('#aiKey').value.trim(),
    baseUrl: $('#aiBaseUrl').value.trim(),
    model: $('#aiModel').value.trim()
  };
  try {
    await api('/api/ai', { method: 'POST', body: JSON.stringify(body) });
    hideModal($('#aiModal'));
    toast('AI 设置已保存 ✅');
  } catch (e) { toast('失败: ' + e.message); }
});

loadAll();

const $ = (s) => document.querySelector(s);
let state = { demoMode: false, accounts: [], messages: [] };

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
    render();
  } catch (e) {
    toast('加载失败: ' + e.message);
  }
}

function render() {
  $('#demoBadge').hidden = !state.demoMode;
  const acc = $('#accounts');
  if (state.accounts.length === 0) {
    acc.innerHTML = '<p class="empty">还没有账户，点右上角 ＋ 添加</p>';
  } else {
    acc.innerHTML = state.accounts.map(a =>
      `<div class="acc-chip"><span class="dot" style="background:${providerColor(a.provider)}"></span>${escapeHtml(a.displayName || a.email)}<small>${escapeHtml(a.provider)}</small></div>`
    ).join('');
  }
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
          ${m.read ? '' : '<span class="unread">●</span>'}
        </div>
        <div class="card-subject">${escapeHtml(m.subject)}</div>
        <div class="card-from">${escapeHtml(m.from)}</div>
        <div class="card-time">${fmtTime(m.sentDate)}</div>
      </div>`
    ).join('');
    inbox.querySelectorAll('.card').forEach(c =>
      c.addEventListener('click', () => openMessage(decodeURIComponent(c.dataset.id))));
  }
}

async function openMessage(id) {
  try {
    const m = await api('/api/messages/' + encodeURIComponent(id));
    $('#msgSubject').textContent = m.subject || '(无主题)';
    $('#msgMeta').innerHTML = `<b>发件人：</b>${escapeHtml(m.from)}<br><b>收件人：</b>${escapeHtml(m.to || '')}<br><b>时间：</b>${fmtTime(m.sentDate)}`;
    $('#msgBody').srcdoc = m.bodyHtml || escapeHtml(m.bodyText || '');
    showModal('#msgModal');
  } catch (e) { toast(e.message); }
}

function showModal(sel) { $(sel).hidden = false; }
function hideModal(el) { el.hidden = true; }
document.querySelectorAll('[data-close]').forEach(b => b.addEventListener('click', e => hideModal(e.target.closest('.modal'))));
document.querySelectorAll('.modal').forEach(m => m.addEventListener('click', e => { if (e.target === m) hideModal(m); }));

$('#refreshBtn').addEventListener('click', loadAll);
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

$('#composeBtn').addEventListener('click', () => {
  $('#composeFrom').innerHTML = state.accounts.map(a =>
    `<option value="${a.id}">${escapeHtml(a.displayName || a.email)}</option>`).join('');
  showModal('#composeModal');
});
$('#composeSubmit').addEventListener('click', async () => {
  const accountId = $('#composeFrom').value;
  const to = $('#composeTo').value.trim();
  const subject = $('#composeSubject').value.trim();
  const bodyText = $('#composeBody').value;
  if (!accountId || !to) { toast('请选择发件账户并填收件人'); return; }
  try {
    const r = await api('/api/send', { method: 'POST', body: JSON.stringify({ accountId, to, subject, body: bodyText }) });
    hideModal($('#composeModal'));
    if (r.demo) toast('演示模式：已模拟发送（未真正联网）');
    else toast('已发送 ✅');
    $('#composeTo').value = $('#composeSubject').value = $('#composeBody').value = '';
  } catch (e) { toast('失败: ' + e.message); }
});

loadAll();

/**
 * Latchi IPTV - Google Sheet API v3 FULL
 *
 * هذا سكريبت واحد يخدم التطبيقين معاً:
 * 1) تطبيق المشاهدة: التحقق بالكود وجلب playlist_url
 * 2) تطبيق التحكم Dashboard: إضافة أكواد، رابط موحد لكل المستخدمين، قائمة المستخدمين، تعديل/تجديد/حذف
 *
 * يدعم جدولك الحالي:
 * code | status | max_devices | devices | expires_at | playlist_url | name
 *
 * مهم بعد لصق السكريبت:
 * Deploy > Manage deployments > Edit > New version > Deploy
 */

const SPREADSHEET_ID = ''; // اتركه فارغ إذا السكريبت مربوط بنفس Google Sheet
const ADMIN_SECRET = 'LatchiAdmin2026';
const CODES_SHEET_NAMES = ['codes', 'Codes', 'CODES'];
const CONFIG_SHEET_NAMES = ['Config', 'config', 'CONFIG'];
const DEFAULT_HEADERS = ['code', 'status', 'max_devices', 'devices', 'expires_at', 'playlist_url', 'name'];

function doGet(e) {
  try {
    const p = (e && e.parameter) ? e.parameter : {};
    const action = String(p.action || '').trim();
    if (action) return handleAdminAction_(action, p);
    return handleValidateCode_(p);
  } catch (err) {
    return jsonOut({ success: false, message: String(err && err.message ? err.message : err) });
  }
}

/* =========================================================
 * تطبيق المشاهدة: ?code=XXXX&device_id=YYYY
 * =======================================================*/
function handleValidateCode_(p) {
  const code = String(p.code || '').trim();
  const deviceId = String(p.device_id || p.device || '').trim();

  if (!code || !deviceId) {
    return jsonOut({ success: false, message: 'Missing code or device' });
  }

  const ctx = getCodesContext_();
  const found = findRowByCode_(ctx.values, ctx.col, code);
  if (found < 0) {
    return jsonOut({ success: false, message: 'Invalid code' });
  }

  const row = ctx.values[found];
  const status = getCell_(row, ctx.col, 'status', 'active');
  if (!isActive_(status)) {
    return jsonOut({ success: false, message: 'Code disabled or inactive', status: String(status) });
  }

  const expiresAt = normalizeDate_(getCell_(row, ctx.col, 'expires_at', ''));
  if (expiresAt && isExpired_(expiresAt)) {
    return jsonOut({ success: false, message: 'Subscription expired', expires_at: expiresAt });
  }

  const maxDevices = positiveInt_(getCell_(row, ctx.col, 'max_devices', '1'), 1);
  let devices = splitDevices_(getCell_(row, ctx.col, 'devices', ''));

  if (devices.indexOf(deviceId) < 0) {
    if (devices.length >= maxDevices) {
      return jsonOut({
        success: false,
        message: 'Device limit reached',
        max_devices: maxDevices,
        devices_count: devices.length
      });
    }
    devices.push(deviceId);
    setCellByKey_(ctx.sh, found, ctx.col, 'devices', devices.join(','));
  }

  const playlistUrl = getEffectivePlaylistUrl_(row, ctx.values, ctx.col);
  if (!playlistUrl) {
    return jsonOut({ success: false, message: 'playlist_url empty' });
  }

  return jsonOut({
    success: true,
    message: 'OK',
    code: code,
    status: 'active',
    name: getCell_(row, ctx.col, 'name', 'User ' + code),
    playlist_url: playlistUrl,
    expires_at: expiresAt,
    max_devices: maxDevices,
    devices_count: devices.length
  });
}

/* =========================================================
 * تطبيق التحكم Dashboard: ?action=...&secret=...
 * =======================================================*/
function handleAdminAction_(action, p) {
  const secret = String(p.secret || '').trim();
  if (secret !== ADMIN_SECRET) {
    return jsonOut({ success: false, message: 'Unauthorized admin secret' });
  }

  const ctx = getCodesContext_();

  switch (action) {
    case 'add_code':
      return adminAddCode_(ctx, p);
    case 'update_master_url':
    case 'set_default_playlist':
      return adminUpdateMasterUrl_(ctx, p);
    case 'get_all_users':
      return adminGetAllUsers_(ctx);
    case 'update_status':
      return adminUpdateStatus_(ctx, p);
    case 'edit_user':
      return adminEditUser_(ctx, p);
    case 'renew_user':
      return adminRenewUser_(ctx, p);
    case 'delete_user':
      return adminDeleteUser_(ctx, p);
    case 'reset_devices':
      return adminResetDevices_(ctx, p);
    default:
      return jsonOut({ success: false, message: 'Unknown action: ' + action });
  }
}

function adminAddCode_(ctx, p) {
  const code = String(p.code || '').trim();
  const name = String(p.name || ('User ' + code)).trim();
  const playlist = cleanPlaylistUrl_(p.playlist_url || p.m3u_url || p.url || '');
  const expiresAt = normalizeDate_(p.expires_at || '');
  const maxDevices = positiveInt_(p.max_devices || '1', 1);

  if (!code) return jsonOut({ success: false, message: 'code required' });
  if (findRowByCode_(ctx.values, ctx.col, code) >= 0) {
    return jsonOut({ success: false, message: 'Code already exists' });
  }

  const row = new Array(ctx.sh.getLastColumn()).fill('');
  row[ctx.col.code] = code;
  row[ctx.col.status] = 'active';
  row[ctx.col.max_devices] = maxDevices;
  row[ctx.col.devices] = '';
  row[ctx.col.expires_at] = expiresAt;
  row[ctx.col.playlist_url] = playlist; // إذا فارغ يستعمل default_playlist_url من Config عند التحقق
  row[ctx.col.name] = name;

  ctx.sh.appendRow(row);
  return jsonOut({
    success: true,
    message: playlist ? 'Code added with custom playlist_url' : 'Code added; will use default/master playlist_url',
    code: code,
    name: name,
    playlist_url: playlist,
    expires_at: expiresAt,
    max_devices: maxDevices
  });
}

function adminUpdateMasterUrl_(ctx, p) {
  const masterUrl = cleanPlaylistUrl_(p.master_url || p.playlist_url || p.url || '');
  if (!masterUrl) return jsonOut({ success: false, message: 'master_url required' });

  setConfigValue_('default_playlist_url', masterUrl);

  // بما أنك تريد سيرفر واحد لكل المستخدمين: نحدّث كل صف فيه code أيضاً.
  let updated = 0;
  for (let i = 1; i < ctx.values.length; i++) {
    const code = getCell_(ctx.values[i], ctx.col, 'code', '');
    if (!code) continue;
    setCellByKey_(ctx.sh, i, ctx.col, 'playlist_url', masterUrl);
    updated++;
  }

  return jsonOut({
    success: true,
    message: 'Master URL updated for all users',
    playlist_url: masterUrl,
    updated_users: updated
  });
}

function adminGetAllUsers_(ctx) {
  const users = [];
  for (let i = 1; i < ctx.values.length; i++) {
    const row = ctx.values[i];
    const code = getCell_(row, ctx.col, 'code', '');
    if (!code) continue;

    const rawStatus = getCell_(row, ctx.col, 'status', 'active');
    users.push({
      rowIdx: i + 1,
      code: code,
      name: getCell_(row, ctx.col, 'name', 'User ' + code),
      playlistUrl: getEffectivePlaylistUrl_(row, ctx.values, ctx.col),
      rawPlaylistUrl: cleanPlaylistUrl_(getCell_(row, ctx.col, 'playlist_url', '')),
      expiresAt: normalizeDate_(getCell_(row, ctx.col, 'expires_at', '')),
      maxDevices: positiveInt_(getCell_(row, ctx.col, 'max_devices', '1'), 1),
      status: isActive_(rawStatus) ? 'Active' : 'Inactive',
      registeredDevices: getCell_(row, ctx.col, 'devices', 'None') || 'None'
    });
  }
  return jsonOut({ success: true, users: users, count: users.length });
}

function adminUpdateStatus_(ctx, p) {
  const code = String(p.code || '').trim();
  const newStatusRaw = String(p.new_status || p.status || '').trim();
  if (!code) return jsonOut({ success: false, message: 'code required' });

  const rowIdx = findRowByCode_(ctx.values, ctx.col, code);
  if (rowIdx < 0) return jsonOut({ success: false, message: 'Code not found' });

  const newStatus = isActive_(newStatusRaw) ? 'active' : 'inactive';
  setCellByKey_(ctx.sh, rowIdx, ctx.col, 'status', newStatus);
  return jsonOut({ success: true, message: 'Status updated', code: code, status: newStatus });
}

function adminEditUser_(ctx, p) {
  const code = String(p.code || '').trim();
  if (!code) return jsonOut({ success: false, message: 'code required' });

  const rowIdx = findRowByCode_(ctx.values, ctx.col, code);
  if (rowIdx < 0) return jsonOut({ success: false, message: 'Code not found' });

  if (p.name != null) setCellByKey_(ctx.sh, rowIdx, ctx.col, 'name', String(p.name).trim());
  if (p.max_devices != null) setCellByKey_(ctx.sh, rowIdx, ctx.col, 'max_devices', positiveInt_(p.max_devices, 1));
  if (p.expires_at != null) setCellByKey_(ctx.sh, rowIdx, ctx.col, 'expires_at', normalizeDate_(p.expires_at));
  if (p.playlist_url != null) setCellByKey_(ctx.sh, rowIdx, ctx.col, 'playlist_url', cleanPlaylistUrl_(p.playlist_url));

  return jsonOut({ success: true, message: 'User updated', code: code });
}

function adminRenewUser_(ctx, p) {
  const code = String(p.code || '').trim();
  const days = positiveInt_(p.duration_days || '30', 30);
  if (!code) return jsonOut({ success: false, message: 'code required' });

  const rowIdx = findRowByCode_(ctx.values, ctx.col, code);
  if (rowIdx < 0) return jsonOut({ success: false, message: 'Code not found' });

  const currentExpiry = normalizeDate_(getCell_(ctx.values[rowIdx], ctx.col, 'expires_at', ''));
  const base = futureBaseDate_(currentExpiry);
  base.setDate(base.getDate() + days);
  const newExpiry = formatDate_(base);

  setCellByKey_(ctx.sh, rowIdx, ctx.col, 'expires_at', newExpiry);
  setCellByKey_(ctx.sh, rowIdx, ctx.col, 'status', 'active');

  return jsonOut({ success: true, message: 'User renewed', code: code, newExpiry: newExpiry, expires_at: newExpiry });
}

function adminDeleteUser_(ctx, p) {
  const code = String(p.code || '').trim();
  if (!code) return jsonOut({ success: false, message: 'code required' });

  const rowIdx = findRowByCode_(ctx.values, ctx.col, code);
  if (rowIdx < 0) return jsonOut({ success: false, message: 'Code not found' });

  ctx.sh.deleteRow(rowIdx + 1);
  return jsonOut({ success: true, message: 'User deleted', code: code });
}

function adminResetDevices_(ctx, p) {
  const code = String(p.code || '').trim();
  if (!code) return jsonOut({ success: false, message: 'code required' });

  const rowIdx = findRowByCode_(ctx.values, ctx.col, code);
  if (rowIdx < 0) return jsonOut({ success: false, message: 'Code not found' });

  setCellByKey_(ctx.sh, rowIdx, ctx.col, 'devices', '');
  return jsonOut({ success: true, message: 'Devices reset', code: code });
}

/* =========================================================
 * Helpers
 * =======================================================*/
function getSpreadsheet_() {
  if (SPREADSHEET_ID && SPREADSHEET_ID.trim()) return SpreadsheetApp.openById(SPREADSHEET_ID.trim());
  return SpreadsheetApp.getActiveSpreadsheet();
}

function getSheetByNames_(ss, names) {
  for (const n of names) {
    const sh = ss.getSheetByName(n);
    if (sh) return sh;
  }
  return null;
}

function getCodesContext_() {
  const ss = getSpreadsheet_();
  let sh = getSheetByNames_(ss, CODES_SHEET_NAMES);
  if (!sh) sh = ss.insertSheet('codes');

  if (sh.getLastRow() === 0 || sh.getLastColumn() === 0) {
    sh.getRange(1, 1, 1, DEFAULT_HEADERS.length).setValues([DEFAULT_HEADERS]);
  }

  let values = sh.getDataRange().getValues();
  if (values.length === 0) {
    sh.getRange(1, 1, 1, DEFAULT_HEADERS.length).setValues([DEFAULT_HEADERS]);
    values = sh.getDataRange().getValues();
  }

  let headers = values[0].map(h => normalizeHeader_(h));
  let col = indexMap_(headers);

  // إذا أعمدة ناقصة نضيفها بدون ما نخرب ترتيب جدولك الحالي
  const required = {
    code: 'code',
    status: 'status',
    max_devices: 'max_devices',
    devices: 'devices',
    expires_at: 'expires_at',
    playlist_url: 'playlist_url',
    name: 'name'
  };
  Object.keys(required).forEach(key => {
    if (col[key] == null) {
      const newCol = sh.getLastColumn() + 1;
      sh.getRange(1, newCol).setValue(required[key]);
      col[key] = newCol - 1;
    }
  });

  values = sh.getDataRange().getValues();
  headers = values[0].map(h => normalizeHeader_(h));
  col = indexMap_(headers);
  return { ss: ss, sh: sh, values: values, headers: headers, col: col };
}

function normalizeHeader_(h) {
  return String(h || '').trim().toLowerCase().replace(/\s+/g, '_');
}

function indexMap_(headers) {
  const aliases = {
    code: ['code', 'activation_code', 'pin'],
    status: ['status', 'active', 'enabled', 'valid'],
    max_devices: ['max_devices', 'maxdevices', 'devices_limit', 'limit'],
    devices: ['devices', 'device_ids', 'device_id'],
    expires_at: ['expires_at', 'expiry', 'expire', 'end_date', 'valid_until'],
    playlist_url: ['playlist_url', 'm3u_url', 'm3u', 'url', 'playlist', 'link', 'server_link'],
    name: ['name', 'user_name', 'client', 'customer', 'title'],
    server: ['server', 'server_url', 'host', 'portal'],
    username: ['username', 'user', 'login'],
    password: ['password', 'pass']
  };
  const out = {};
  Object.keys(aliases).forEach(key => {
    for (const a of aliases[key]) {
      const idx = headers.indexOf(a);
      if (idx >= 0) { out[key] = idx; break; }
    }
  });
  return out;
}

function findRowByCode_(values, col, code) {
  if (col.code == null) return -1;
  const target = String(code || '').trim().toLowerCase();
  for (let i = 1; i < values.length; i++) {
    const rowCode = String(values[i][col.code] || '').trim().toLowerCase();
    if (rowCode && rowCode === target) return i;
  }
  return -1;
}

function getCell_(row, col, key, fallback) {
  if (col[key] == null) return fallback;
  const v = row[col[key]];
  if (v == null) return fallback;
  const s = String(v).trim();
  return s || fallback;
}

function setCellByKey_(sh, rowIndex0, col, key, value) {
  if (col[key] == null) throw new Error('Missing column: ' + key);
  sh.getRange(rowIndex0 + 1, col[key] + 1).setValue(value);
}

function splitDevices_(value) {
  return String(value || '')
    .split(',')
    .map(s => s.trim())
    .filter(Boolean);
}

function isActive_(value) {
  const s = String(value || '').trim().toLowerCase();
  return s === '' || s === 'active' || s === 'true' || s === '1' || s === 'yes' || s === 'ok' || s === 'success';
}

function positiveInt_(value, fallback) {
  const n = parseInt(String(value || '').trim(), 10);
  return (isNaN(n) || n <= 0) ? fallback : n;
}

function normalizeDate_(value) {
  if (!value) return '';
  if (Object.prototype.toString.call(value) === '[object Date]' && !isNaN(value)) {
    return Utilities.formatDate(value, Session.getScriptTimeZone(), 'yyyy-MM-dd');
  }

  const s = String(value).trim();
  let m = s.match(/^(\d{1,2})[-\/](\d{1,2})[-\/](\d{4})$/); // dd-MM-yyyy
  if (m) return `${m[3]}-${pad2_(m[2])}-${pad2_(m[1])}`;

  m = s.match(/^(\d{4})[-\/](\d{1,2})[-\/](\d{1,2})$/); // yyyy-MM-dd
  if (m) return `${m[1]}-${pad2_(m[2])}-${pad2_(m[3])}`;

  return s;
}

function pad2_(x) {
  return String(x).padStart(2, '0');
}

function isExpired_(yyyyMmDd) {
  const d = new Date(yyyyMmDd + 'T23:59:59');
  if (isNaN(d.getTime())) return false;
  return Date.now() > d.getTime();
}

function futureBaseDate_(yyyyMmDd) {
  const now = new Date();
  const current = new Date(yyyyMmDd + 'T12:00:00');
  if (!isNaN(current.getTime()) && current.getTime() > now.getTime()) return current;
  return now;
}

function formatDate_(date) {
  return Utilities.formatDate(date, Session.getScriptTimeZone(), 'yyyy-MM-dd');
}

function cleanPlaylistUrl_(value) {
  if (!value) return '';
  const lines = String(value)
    .replace(/&amp;/g, '&')
    .split(/\r?\n/)
    .map(s => s.trim())
    .filter(Boolean);
  for (const line of lines) {
    if (/^https?:\/\//i.test(line)) return line;
  }
  return lines[0] || '';
}

function getEffectivePlaylistUrl_(row, values, col) {
  let playlistUrl = cleanPlaylistUrl_(getCell_(row, col, 'playlist_url', ''));
  if (!playlistUrl) playlistUrl = cleanPlaylistUrl_(getConfigValue_('default_playlist_url'));
  if (!playlistUrl) playlistUrl = cleanPlaylistUrl_(findBlankCodeDefaultPlaylist_(values, col));

  if (!playlistUrl) {
    const server = getCell_(row, col, 'server', '');
    const username = getCell_(row, col, 'username', '');
    const password = getCell_(row, col, 'password', '');
    if (server && username && password) playlistUrl = buildM3uUrl_(server, username, password);
  }
  return playlistUrl;
}

function getConfigSheet_() {
  const ss = getSpreadsheet_();
  let sh = getSheetByNames_(ss, CONFIG_SHEET_NAMES);
  if (!sh) {
    sh = ss.insertSheet('Config');
    sh.getRange(1, 1, 1, 2).setValues([['key', 'value']]);
  }
  return sh;
}

function getConfigValue_(key) {
  const sh = getConfigSheet_();
  const values = sh.getDataRange().getValues();
  for (let i = 0; i < values.length; i++) {
    if (String(values[i][0] || '').trim().toLowerCase() === key.toLowerCase()) {
      return String(values[i][1] || '').trim();
    }
  }
  return '';
}

function setConfigValue_(key, value) {
  const sh = getConfigSheet_();
  const values = sh.getDataRange().getValues();
  for (let i = 0; i < values.length; i++) {
    if (String(values[i][0] || '').trim().toLowerCase() === key.toLowerCase()) {
      sh.getRange(i + 1, 2).setValue(value);
      return;
    }
  }
  sh.appendRow([key, value]);
}

function findBlankCodeDefaultPlaylist_(values, col) {
  if (col.playlist_url == null || col.code == null) return '';
  for (let i = 1; i < values.length; i++) {
    const code = String(values[i][col.code] || '').trim();
    const url = String(values[i][col.playlist_url] || '').trim();
    if (!code && url) return url;
  }
  return '';
}

function buildM3uUrl_(server, username, password) {
  let s = String(server || '').trim();
  if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
  s = s.replace(/\/+$/, '');
  return s + '/get.php?username=' + encodeURIComponent(username) +
    '&password=' + encodeURIComponent(password) +
    '&type=m3u_plus&output=ts';
}

function jsonOut(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

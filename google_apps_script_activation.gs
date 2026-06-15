/**
 * Latchi IPTV - Google Sheet API v3 FULL + App Update + MAC/Stalker Support
 *
 * يخدم:
 * 1) تطبيق المشاهدة: validate code + playlist_url / xtream / mac portal
 * 2) Dashboard: إدارة المستخدمين + تحديث السيرفر + تحديث APK
 *
 * جدول codes يدعم هذه الأعمدة:
 * code | status | max_devices | devices | expires_at | playlist_url | name | source_type | portal_url | mac_address | server | username | password
 *
 * source_type:
 * - m3u
 * - xtream
 * - mac
 *
 * ملاحظة مهمة:
 * MAC/Stalker لا يتحول دائماً إلى M3U من السكريبت وحدو. السكريبت يرجع portal_url + mac_address للتطبيق.
 * لازم التطبيق يدعم Stalker/MAC باش يشغل القنوات.
 */

const SPREADSHEET_ID = ''; // اتركه فارغ إذا السكريبت مربوط بنفس Google Sheet
const ADMIN_SECRET = 'LatchiAdmin2026';

const CODES_SHEET_NAMES = ['codes', 'Codes', 'CODES'];
const CONFIG_SHEET_NAMES = ['Config', 'config', 'CONFIG'];

const DEFAULT_HEADERS = [
  'code', 'status', 'max_devices', 'devices', 'expires_at', 'playlist_url', 'name',
  'source_type', 'portal_url', 'mac_address', 'server', 'username', 'password'
];

function doGet(e) {
  try {
    const p = (e && e.parameter) ? e.parameter : {};
    const action = String(p.action || '').trim();

    if (action === 'get_app_update' || action === 'check_app_update') {
      return handleGetAppUpdate_(p);
    }

    if (action) return handleAdminAction_(action, p);
    return handleValidateCode_(p);
  } catch (err) {
    return jsonOut({ success: false, message: String(err && err.message ? err.message : err) });
  }
}

/* =========================================================
 * تطبيق المشاهدة: ?code=XXXX&device_id=YYYY
 * ======================================================= */
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

  const source = getEffectiveSource_(row, ctx.values, ctx.col);

  if (!source.playlist_url && source.source_type !== 'mac') {
    return jsonOut({ success: false, message: 'source empty: playlist_url / xtream / mac not configured' });
  }

  if (source.source_type === 'mac' && (!source.portal_url || !source.mac_address)) {
    return jsonOut({ success: false, message: 'MAC source requires portal_url and mac_address' });
  }

  return jsonOut({
    success: true,
    message: 'OK',
    code: code,
    status: 'active',
    name: getCell_(row, ctx.col, 'name', 'User ' + code),
    expires_at: expiresAt,
    max_devices: maxDevices,
    devices_count: devices.length,

    // القديم باش ما نكسرش التطبيق الحالي
    playlist_url: source.playlist_url,

    // الجديد
    source_type: source.source_type,
    sourceType: source.source_type,
    portal_url: source.portal_url,
    portalUrl: source.portal_url,
    mac_address: source.mac_address,
    macAddress: source.mac_address,
    server: source.server,
    username: source.username,
    password: source.password
  });
}

/* =========================================================
 * Public App Update: ?action=get_app_update&version_code=123
 * ======================================================= */
function handleGetAppUpdate_(p) {
  const latestCode = positiveInt_(getConfigValue_('app_update_version_code'), 0);
  const currentCode = positiveInt_(p.version_code || p.current_version_code || '0', 0);
  const latestName = getConfigValue_('app_update_version_name') || '';
  const apkUrl = cleanUrl_(getConfigValue_('app_update_apk_url'));
  const forceUpdate = isTruthy_(getConfigValue_('app_update_force'));

  const notes = {
    ar: getConfigValue_('app_update_notes_ar') || 'تحديث جديد متوفر لتطبيق LATCHI IPTV.',
    fr: getConfigValue_('app_update_notes_fr') || 'Nouvelle mise à jour disponible pour LATCHI IPTV.',
    en: getConfigValue_('app_update_notes_en') || 'A new update is available for LATCHI IPTV.'
  };

  return jsonOut({
    success: true,
    update_available: latestCode > currentCode && !!apkUrl,
    versionCode: latestCode,
    versionName: latestName,
    apkUrl: apkUrl,
    url: apkUrl,
    forceUpdate: forceUpdate,
    notes: notes,
    releaseNotes: notes
  });
}

/* =========================================================
 * Dashboard Admin
 * ======================================================= */
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
    case 'update_master_mac':
    case 'set_default_mac':
      return adminUpdateMasterMac_(ctx, p);
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
    case 'set_app_update':
    case 'update_app_update':
      return adminSetAppUpdate_(p);
    default:
      return jsonOut({ success: false, message: 'Unknown action: ' + action });
  }
}

function adminAddCode_(ctx, p) {
  const code = String(p.code || '').trim();
  const name = String(p.name || ('User ' + code)).trim();

  const sourceType = normalizeSourceType_(p.source_type || p.sourceType || '');
  const playlist = cleanUrl_(p.playlist_url || p.m3u_url || p.url || '');
  const portalUrl = cleanPortalUrl_(p.portal_url || p.portal || p.stalker_portal || '');
  const macAddress = normalizeMac_(p.mac_address || p.mac || p.mac_code || '');
  const server = cleanPortalUrl_(p.server || p.server_url || '');
  const username = String(p.username || p.user || '').trim();
  const password = String(p.password || p.pass || '').trim();

  const expiresAt = normalizeDate_(p.expires_at || '');
  const maxDevices = positiveInt_(p.max_devices || '1', 1);

  if (!code) return jsonOut({ success: false, message: 'code required' });
  if (findRowByCode_(ctx.values, ctx.col, code) >= 0) {
    return jsonOut({ success: false, message: 'Code already exists' });
  }

  const finalSourceType = decideSourceType_(sourceType, playlist, portalUrl, macAddress, server, username, password);

  if (finalSourceType === 'mac' && (!portalUrl || !macAddress)) {
    return jsonOut({ success: false, message: 'MAC requires portal_url and mac_address' });
  }

  const row = new Array(ctx.sh.getLastColumn()).fill('');
  row[ctx.col.code] = code;
  row[ctx.col.status] = 'active';
  row[ctx.col.max_devices] = maxDevices;
  row[ctx.col.devices] = '';
  row[ctx.col.expires_at] = expiresAt;
  row[ctx.col.playlist_url] = playlist;
  row[ctx.col.name] = name;
  row[ctx.col.source_type] = finalSourceType;
  row[ctx.col.portal_url] = portalUrl;
  row[ctx.col.mac_address] = macAddress;
  row[ctx.col.server] = server;
  row[ctx.col.username] = username;
  row[ctx.col.password] = password;

  ctx.sh.appendRow(row);
  return jsonOut({
    success: true,
    message: 'Code added',
    code: code,
    name: name,
    source_type: finalSourceType,
    playlist_url: playlist,
    portal_url: portalUrl,
    mac_address: macAddress,
    server: server,
    username: username,
    expires_at: expiresAt,
    max_devices: maxDevices
  });
}

function adminUpdateMasterUrl_(ctx, p) {
  const masterUrl = cleanUrl_(p.master_url || p.playlist_url || p.url || '');
  if (!masterUrl) return jsonOut({ success: false, message: 'master_url required' });

  setConfigValue_('default_playlist_url', masterUrl);
  setConfigValue_('default_source_type', 'm3u');

  let updated = 0;
  for (let i = 1; i < ctx.values.length; i++) {
    const code = getCell_(ctx.values[i], ctx.col, 'code', '');
    if (!code) continue;
    setCellByKey_(ctx.sh, i, ctx.col, 'playlist_url', masterUrl);
    setCellByKey_(ctx.sh, i, ctx.col, 'source_type', 'm3u');
    updated++;
  }

  return jsonOut({ success: true, message: 'Master M3U URL updated for all users', playlist_url: masterUrl, updated_users: updated });
}

function adminUpdateMasterMac_(ctx, p) {
  const portalUrl = cleanPortalUrl_(p.portal_url || p.portal || p.stalker_portal || p.url || '');
  const macAddress = normalizeMac_(p.mac_address || p.mac || p.mac_code || '');

  if (!portalUrl) return jsonOut({ success: false, message: 'portal_url required' });
  if (!macAddress) return jsonOut({ success: false, message: 'mac_address required' });

  setConfigValue_('default_source_type', 'mac');
  setConfigValue_('default_portal_url', portalUrl);
  setConfigValue_('default_mac_address', macAddress);

  let updated = 0;
  for (let i = 1; i < ctx.values.length; i++) {
    const code = getCell_(ctx.values[i], ctx.col, 'code', '');
    if (!code) continue;
    setCellByKey_(ctx.sh, i, ctx.col, 'source_type', 'mac');
    setCellByKey_(ctx.sh, i, ctx.col, 'portal_url', portalUrl);
    setCellByKey_(ctx.sh, i, ctx.col, 'mac_address', macAddress);
    setCellByKey_(ctx.sh, i, ctx.col, 'playlist_url', '');
    updated++;
  }

  return jsonOut({ success: true, message: 'Master MAC portal updated for all users', portal_url: portalUrl, mac_address: macAddress, updated_users: updated });
}

function adminGetAllUsers_(ctx) {
  const users = [];
  for (let i = 1; i < ctx.values.length; i++) {
    const row = ctx.values[i];
    const code = getCell_(row, ctx.col, 'code', '');
    if (!code) continue;

    const rawStatus = getCell_(row, ctx.col, 'status', 'active');
    const source = getEffectiveSource_(row, ctx.values, ctx.col);
    users.push({
      rowIdx: i + 1,
      code: code,
      name: getCell_(row, ctx.col, 'name', 'User ' + code),
      sourceType: source.source_type,
      source_type: source.source_type,
      playlistUrl: source.playlist_url,
      playlist_url: source.playlist_url,
      portalUrl: source.portal_url,
      portal_url: source.portal_url,
      macAddress: source.mac_address,
      mac_address: source.mac_address,
      server: source.server,
      username: source.username,
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

  if (p.source_type != null || p.sourceType != null) setCellByKey_(ctx.sh, rowIdx, ctx.col, 'source_type', normalizeSourceType_(p.source_type || p.sourceType));
  if (p.playlist_url != null || p.m3u_url != null || p.url != null) setCellByKey_(ctx.sh, rowIdx, ctx.col, 'playlist_url', cleanUrl_(p.playlist_url || p.m3u_url || p.url || ''));
  if (p.portal_url != null || p.portal != null || p.stalker_portal != null) setCellByKey_(ctx.sh, rowIdx, ctx.col, 'portal_url', cleanPortalUrl_(p.portal_url || p.portal || p.stalker_portal || ''));
  if (p.mac_address != null || p.mac != null || p.mac_code != null) setCellByKey_(ctx.sh, rowIdx, ctx.col, 'mac_address', normalizeMac_(p.mac_address || p.mac || p.mac_code || ''));
  if (p.server != null || p.server_url != null) setCellByKey_(ctx.sh, rowIdx, ctx.col, 'server', cleanPortalUrl_(p.server || p.server_url || ''));
  if (p.username != null || p.user != null) setCellByKey_(ctx.sh, rowIdx, ctx.col, 'username', String(p.username || p.user || '').trim());
  if (p.password != null || p.pass != null) setCellByKey_(ctx.sh, rowIdx, ctx.col, 'password', String(p.password || p.pass || '').trim());

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

function adminSetAppUpdate_(p) {
  const versionCode = positiveInt_(p.version_code || p.versionCode || '0', 0);
  const versionName = String(p.version_name || p.versionName || '').trim();
  const apkUrl = cleanUrl_(p.apk_url || p.apkUrl || p.url || '');
  const forceUpdate = isTruthy_(p.force_update || p.forceUpdate || 'false');

  if (versionCode <= 0) return jsonOut({ success: false, message: 'version_code required' });
  if (!apkUrl) return jsonOut({ success: false, message: 'apk_url required' });

  setConfigValue_('app_update_version_code', String(versionCode));
  setConfigValue_('app_update_version_name', versionName);
  setConfigValue_('app_update_apk_url', apkUrl);
  setConfigValue_('app_update_force', forceUpdate ? 'true' : 'false');
  if (p.notes_ar != null) setConfigValue_('app_update_notes_ar', String(p.notes_ar));
  if (p.notes_fr != null) setConfigValue_('app_update_notes_fr', String(p.notes_fr));
  if (p.notes_en != null) setConfigValue_('app_update_notes_en', String(p.notes_en));

  return jsonOut({ success: true, message: 'App update info saved', versionCode: versionCode, versionName: versionName, apkUrl: apkUrl, forceUpdate: forceUpdate });
}

/* =========================================================
 * Source Helpers
 * ======================================================= */
function getEffectiveSource_(row, values, col) {
  const explicitType = normalizeSourceType_(getCell_(row, col, 'source_type', ''));
  let playlistUrl = cleanUrl_(getCell_(row, col, 'playlist_url', ''));
  let portalUrl = cleanPortalUrl_(getCell_(row, col, 'portal_url', ''));
  let macAddress = normalizeMac_(getCell_(row, col, 'mac_address', ''));
  let server = cleanPortalUrl_(getCell_(row, col, 'server', ''));
  let username = getCell_(row, col, 'username', '');
  let password = getCell_(row, col, 'password', '');

  if (!playlistUrl) playlistUrl = cleanUrl_(getConfigValue_('default_playlist_url'));
  if (!portalUrl) portalUrl = cleanPortalUrl_(getConfigValue_('default_portal_url'));
  if (!macAddress) macAddress = normalizeMac_(getConfigValue_('default_mac_address'));
  if (!playlistUrl) playlistUrl = cleanUrl_(findBlankCodeDefaultPlaylist_(values, col));

  if (!playlistUrl && server && username && password) {
    playlistUrl = buildM3uUrl_(server, username, password);
  }

  const sourceType = decideSourceType_(explicitType || normalizeSourceType_(getConfigValue_('default_source_type')), playlistUrl, portalUrl, macAddress, server, username, password);

  return {
    source_type: sourceType,
    playlist_url: playlistUrl,
    portal_url: portalUrl,
    mac_address: macAddress,
    server: server,
    username: username,
    password: password
  };
}

function decideSourceType_(explicitType, playlistUrl, portalUrl, macAddress, server, username, password) {
  if (explicitType) return explicitType;
  if (portalUrl && macAddress) return 'mac';
  if (server && username && password) return 'xtream';
  if (playlistUrl) return 'm3u';
  return 'm3u';
}

function normalizeSourceType_(value) {
  const s = String(value || '').trim().toLowerCase();
  if (['mac', 'stalker', 'portal', 'mag'].indexOf(s) >= 0) return 'mac';
  if (['xtream', 'xc', 'xstream'].indexOf(s) >= 0) return 'xtream';
  if (['m3u', 'playlist', 'url'].indexOf(s) >= 0) return 'm3u';
  return '';
}

function normalizeMac_(value) {
  let s = String(value || '').trim().toUpperCase();
  if (!s) return '';
  s = s.replace(/-/g, ':').replace(/\s+/g, '');

  // إذا دخلها بلا : نحاول نقسمها كل زوج حروف
  const compact = s.replace(/:/g, '');
  if (/^[0-9A-F]{12}$/.test(compact)) {
    return compact.match(/.{1,2}/g).join(':');
  }

  // نقبل الشكل العادي. إذا فيه حروف خارج HEX نرجعه كما هو باش ما نضيعش إدخال المستخدم.
  if (/^([0-9A-F]{2}:){5}[0-9A-F]{2}$/.test(s)) return s;
  return s;
}

function cleanPortalUrl_(value) {
  let s = cleanUrl_(value);
  if (!s) return '';
  s = s.replace(/\/+$/, '');
  return s;
}

/* =========================================================
 * Sheet Helpers
 * ======================================================= */
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

  const required = {
    code: 'code',
    status: 'status',
    max_devices: 'max_devices',
    devices: 'devices',
    expires_at: 'expires_at',
    playlist_url: 'playlist_url',
    name: 'name',
    source_type: 'source_type',
    portal_url: 'portal_url',
    mac_address: 'mac_address',
    server: 'server',
    username: 'username',
    password: 'password'
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
    source_type: ['source_type', 'type', 'source', 'account_type'],
    portal_url: ['portal_url', 'portal', 'stalker_portal', 'mag_portal'],
    mac_address: ['mac_address', 'mac', 'mac_code', 'device_mac'],
    server: ['server', 'server_url', 'host', 'portal_host'],
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
  return String(value || '').split(',').map(s => s.trim()).filter(Boolean);
}

function isActive_(value) {
  const s = String(value || '').trim().toLowerCase();
  return s === '' || s === 'active' || s === 'true' || s === '1' || s === 'yes' || s === 'ok' || s === 'success';
}

function isTruthy_(value) {
  const s = String(value || '').trim().toLowerCase();
  return s === 'true' || s === '1' || s === 'yes' || s === 'on' || s === 'force';
}

function positiveInt_(value, fallback) {
  const n = parseInt(String(value || '').trim(), 10);
  return (isNaN(n) || n < 0) ? fallback : n;
}

function normalizeDate_(value) {
  if (!value) return '';
  if (Object.prototype.toString.call(value) === '[object Date]' && !isNaN(value)) {
    return Utilities.formatDate(value, Session.getScriptTimeZone(), 'yyyy-MM-dd');
  }

  const s = String(value).trim();
  let m = s.match(/^(\d{1,2})[-\/](\d{1,2})[-\/](\d{4})$/);
  if (m) return `${m[3]}-${pad2_(m[2])}-${pad2_(m[1])}`;

  m = s.match(/^(\d{4})[-\/](\d{1,2})[-\/](\d{1,2})$/);
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

function cleanUrl_(value) {
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
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

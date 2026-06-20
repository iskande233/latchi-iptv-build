# 🛡️ تقرير الإصلاح: مشكلة Room Stale Cache & Per-Type Freshness

**التاريخ:** 20 جوان 2026
**المستودع:** `iskande233/latchi-iptv-build`
**Branch:** `fix/room-freshness-and-stale-cache`
**Commits:** `469e7ce` + `297bebf`

---

## 🎯 المشكلة التي تم حلها

### الأعراض (قبل الإصلاح):
1. ✅ القنوات تظهر بسرعة في الواجهة
2. ❌ لكن لا تشتغل عند الضغط عليها (stream URLs قديمة)
3. ❌ بعد تعميم سيرفر جديد من لوحة التحكم، التطبيق يعرض نفس البيانات القديمة
4. ❌ "السيرفر لا يتغير" ظاهرياً

### السبب الجذري:
`CatalogSyncStateEntity` كان يخزن فقط:
- `serverRevision` (رقم شامل)
- `lastSyncAt`

لكن **لا يخزن per-type revision/hash** لكل catalog (live, bein, movies, series).

النتيجة:
- `fetchMeta()` يستدعى بـ `serverRevision` الشامل بدل `catalog_revision_live`
- مقارنة `not_modified` خاطئة — السيرفر يرد بـ `not_modified=false` دائماً لأن الأرقام لا تتطابق
- أو يرد بـ `not_modified=true` بشكل خاطئ عندما لا يجب
- الـ UI يقرأ Room بدون فحص صلاحية → يعرض بيانات قديمة

---

## 🛠️ الحل المُطبَّق

### 1. توسيع `CatalogSyncStateEntity` (version 2)

أضفنا لكل نوع (live, bein, movies, series):
- `liveRevision`, `liveHash`, `liveUrl`, `liveLastSyncAt`
- `beinRevision`, `beinHash`, `beinUrl`, `beinLastSyncAt`
- `moviesRevision`, `moviesHash`, `moviesUrl`, `moviesLastSyncAt`
- `seriesRevision`, `seriesHash`, `seriesUrl`, `seriesLastSyncAt`

مع method `freshnessFor(type: String): TypeFreshness` للوصول الموحد.

### 2. إضافة `CatalogType` enum

يربط بين:
- **script type** (ما يستخدمه السكريبت: `"live"`, `"bein"`, `"movies"`, `"series"`)
- **internal type** (ما يستخدمه التطبيق: `"live"`, `"movie"`, `"series"`)

### 3. إضافة APIs جديدة في `CatalogRepository`

#### `isCatalogFresh(context, profileId, catalogType): FreshnessResult`
- يقارن per-type revision/hash المحلي مع server
- يُرجع `FreshnessResult` كامل (isFresh, reason, localRev, serverRev, networkReachable)

#### `getChannelsByTypeSmart(context, profileId, catalogType, onUpdated)`
- **Fast path**: يُرجع Room فوراً (Offline-First)
- **Background**: يفحص freshness
- **إذا stale**: يعيد sync صامت
- **عند التحديث**: يستدعي `onUpdated` بالقائمة الجديدة

#### `invalidateAllCatalogs()` / `invalidateCatalog()`
- مسح Room لإجبار re-sync

### 4. تحديث `syncTypeFromApi()`
- كان يستخدم `profile.serverRevision` (شامل) — خطأ
- الآن يستخدم `localState.freshnessFor(contentType).revision` — صحيح

### 5. تحديث `ServerSyncManager`
- عند اكتشاف تغيير في server revision أو URL:
  - كان: `ChannelCache.clear()` فقط
  - الآن: `ChannelCache.clear()` + **`CatalogRepository.invalidateAllCatalogs()`**
- هذا يضمن أن الـ freshness check التالي سيرى mismatch → يعمل re-sync

### 6. تحديث المستهلكين
- `HomeFragment.loadCachedOrFetch()` → يستخدم smart getter
- `HomeFragment.refreshChannelsSilently()` → يمسح Room قبل re-sync
- `TvLivePreviewActivity.loadLiveChannelsSmart()` → يستخدم smart getter
- `ChannelListActivity.loadCachedOrFetch()` → يستخدم smart getter

---

## 📊 الملفات المعدّلة

| الملف | التغييرات |
|------|-----------|
| `CatalogSyncStateEntity.kt` | +57 سطر (per-type fields + freshnessFor()) |
| `CatalogDatabase.kt` | version 1 → 2 |
| `CatalogRepository.kt` | +267 سطر (FreshnessResult, smart getters, sync logic) |
| `HomeFragment.kt` | +85 سطر (smart getter + invalidate) |
| `TvLivePreviewActivity.kt` | +25 سطر (smart getter) |
| `ChannelListActivity.kt` | +22 سطر (smart getter) |
| `ServerSyncManager.kt` | +7 سطر (invalidate on revision change) |
| `CHANGELOG.md` | +40 سطر (توثيق) |

**المجموع:** +503 سطر، -30 سطر، 8 ملفات

---

## 🔄 كيف يعمل الآن (التدفق الجديد)

### السيناريو 1: فتح التطبيق
```
1. UI يطلب القنوات من Room (Offline-First سريع)
2. في الخلفية → getChannelsByTypeSmart يستدعي get_catalog_meta
3. يقارن revision/hash المحلي مع السيرفر
4. إذا match → "البيانات طازجة" (لا شيء)
5. إذا mismatch → re-sync صامت → تحديث UI تلقائياً
```

### السيناريو 2: تعميم سيرفر جديد من لوحة التحكم
```
1. ServerSyncManager يكتشف changed=true
2. يحفظ profile الجديد بالـ revision الجديد
3. يمسح ChannelCache + Room (CatalogRepository.invalidateAllCatalogs)
4. UI التالي يفتح → يجد Room فارغ → يعمل sync كامل
5. البيانات الجديدة + revision/hash الجديد → freshness match
```

### السيناريو 3: رفع prepared catalog واحد (مثلاً live فقط)
```
1. السكريبت يرفع catalog_revision_live فقط
2. التطبيق يستدعى get_catalog_meta لكل نوع:
   - live → revision_changed → re-sync live فقط ✅
   - movies → revision_unchanged → skip ✅
   - series → revision_unchanged → skip ✅
3. الكفاءة: re-sync selective وليس full
```

---

## ⚠️ ملاحظات مهمة

### Migration
- CatalogDatabase version: 1 → 2
- `fallbackToDestructiveMigration()` مفعّل → الكاش يُمسح عند الترقية
- البيانات قابلة للاستعادة عبر re-sync تلقائي

### Backwards Compatibility
- `saveChannels()` يضيف params جديدة بقيم افتراضية → لا يكسر callers الحاليين
- `getChannelsByType()` و `getChannelsByTypeBlocking()` ما زالا موجودين
- `hasTypeData()` ما زال موجود

### الأمان
- الـ Smart getter يستخدم `runCatching` في كل مكان
- أي فشل network لا يكسر UI
- `syncNow()` failures → silent (لا يعرض error للمستخدم إذا عنده كاش)

---

## 🚀 الخطوة التالية (اختيارية)

إذا أردت اختبار الحل محلياً:
1. افتح لوحة التحكم → عمم سيرفر جديد
2. افتح التطبيق → يجب أن ترى:
   - الواجهة تفتح بسرعة (Offline-First) ✅
   - ثم تتحدث بصمت عند الـ sync ✅
3. للقنوات التي لا تشتغل → يجب أن تشتغل الآن ✅

**Branch جاهز للـ merge في `main`** عبر Pull Request:
https://github.com/iskande233/latchi-iptv-build/pull/new/fix/room-freshness-and-stale-cache

---

## ✅ الخلاصة

المشكلة لم تكن في فكرة Room/Offline-First نفسها، بل في:
- ❌ تخزين revision/hash غير كافٍ
- ❌ عدم وجود freshness gate قبل عرض Room
- ❌ عدم إبطال Room عند تغيّر السيرفر

كل هذه النقاط الثلاث تم إصلاحها بشكل جذري مع:
- ✅ الحفاظ على سرعة Offline-First
- ✅ ضمان حداثة البيانات
- ✅ كفاءة (re-sync selective وليس full)
- ✅ backwards compatible مع الكود الحالي

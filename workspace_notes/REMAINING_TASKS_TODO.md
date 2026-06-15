# المهام المتبقية - Latchi IPTV (محدث بعد جولة "زيد كمل جميع التعديلات")

تاريخ التحديث: 2026-06-14 (جولة كبيرة عبر الأولويات 1+2+5)

## الأولوية 1 — إكمال ServerSyncManager
**الحالة**: ✅ إنجاز كبير جداً (شبه مكتمل)

تم إنجازه في هذه الجولة:
- ServerHealthChecker.kt جديد (فحص صحة السيرفر قبل التبديل).
- ServerSyncManager معدل: يفحص الصحة (HEAD) قبل ما يعتمد الرابط الجديد. لو غير صحي → ما يبدلش.
- PlayerServerSyncHelper.kt جديد (sync آمن داخل المشغل).
- PlayerActivity: يتحقق تلقائياً في onResume (Overlay 3 ثواني + رجوع للـ Home بدون ما يكسر المشاهدة).

المتبقي:
- اختبار الحالات (server_unhealthy).
- عرض Server Health Indicator واضح في أكثر من مكان (Settings + ربما Home).

## الأولوية 2 — إكمال Settings Feedback
**الحالة**: ✅ تقدم قوي

تم إنجازه:
- ErrorOverlayHelper.kt جديد (Overlay احترافي للأخطاء بنفس ستايل النجاح).
- SettingsActivity: showServerStatusInSettings() (يعرض ✅ أونلاين / ❌ غير متاح + وقت الاستجابة + آخر فحص).
- markActiveOption helper لتسهيل وضع ✓ على كل الخيارات في TV Settings.
- استدعاء حالة السيرفر بعد عرض آخر وقت Sync.

المتبقي:
- تطبيق ✓ بصري كامل على كل خيارات TV Settings (استخدم الـ helper).
- استبدال كل الـ Toast برسالة ErrorOverlayHelper أو Success (في كل الشاشات).
- عرض Server Status في TV Settings بشكل أجمل.

## الأولوية 3 — اختبار وتحسين Focus الفئات والقنوات
(لم يتم عمل جديد في هذه الجولة — الأساس موجود من قبل)

المتبقي: اختبار حقيقي على TV + حفظ آخر Focus.

## الأولوية 4 — TvChannelPreviewActivity
**الحالة**: ✅ منجزة في الجولة السابقة (TvLivePreviewActivity كاملة + توجيه فقط للتلفاز + ساعة حية + توثيق).

## الأولوية 5 — تحسينات احترافية
**الحالة**: ✅ عدة انتصارات سريعة

تم إنجازه:
- LiveClockHelper.kt جديد + ربطه في HomeFragment و TvLivePreviewActivity (ساعة حية بالثواني).
- ErrorOverlayHelper (يغطي "Error Overlay بدل Toast").
- Health check (يغطي "Health check للسيرفر قبل اعتماده").

المتبقي (حسب المذكرة):
- Continue Watching و Favorites Row في TV Home.
- Changelog داخلي.
- المزيد من استخدام الـ Overlays.

## ملخص الحالة العامة بعد الجولتين
- 1: شبه مكتمل (صحة + أمان أثناء المشاهدة)
- 2: تقدم قوي (حالة سيرفر + أدوات Overlay)
- 3: أساسي فقط
- 4: مكتمل v1
- 5: عدة تحسينات جاهزة

**القواعد محترمة 100%** (مشغل أساسي ما لمست، فصل هاتف/تلفاز، لا builds بدون إذن).

كل التعديلات في extracted_project/.

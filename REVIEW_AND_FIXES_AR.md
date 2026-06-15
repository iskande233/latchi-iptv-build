# مراجعة وإصلاحات Latchi IPTV

## المشاكل التي وجدتها

1. **سبب ظهور القنوات الغلط عند اختيار فئة**
   - `ChannelsProvider.setLocalChannels()` كان يرسل كامل القائمة إلى `filteredChannels` مباشرة.
   - بعدها `ChannelListActivity` يطبق فلتر النوع/الفئة.
   - لكن `ChannelsAdapter.updateChannels()` كان يستعمل `DiffUtil` في Coroutine، وأحياناً تحديث "كل القنوات" يتأخر ويرجع فوق تحديث الفئة المختارة.
   - النتيجة: تضغط على قسم/فئة، تبقى تظهر قنوات أخرى حتى تبحث، لأن البحث يطلق فلتر جديد ويغلب التحديث القديم.

2. **سبب البطء**
   - Xtream API كان يجلب live categories ثم vod categories ثم series categories ثم live streams ثم vod streams ثم series streams بشكل متسلسل.
   - هذا يطول خاصة مع قوائم فيها عشرات الآلاف.

3. **مشكلة التخزين المحلي**
   - التطبيق عنده `ChannelCache`، لكن `HomeFragment` و `ChannelListActivity` كانا يفضلان `assets/channels.json` دائماً إذا كان موجوداً، ويتجاهلان كاش الحساب.
   - بما أن `assets/channels.json` موجود وفيه قائمة كبيرة، هذا يسبب أن التطبيق يستعمل قائمة مدمجة بدل قائمة المستخدم/الحساب.

4. **خطأ في تحميل كاش المسلسلات فقط**
   - `ChannelCache.load()` كان يرجع empty إذا لم توجد live/vod files حتى لو كان ملف series موجود.

## الإصلاحات التي طبقتها

### 1. إصلاح فلترة الفئات
- أزلت إرسال كامل القائمة إلى `filteredChannels` داخل `setLocalChannels()` و `fetchM3UFile()`.
- الآن `ChannelListActivity` هو المسؤول عن تطبيق `contentType + category + search` بعد وصول `channels`.

### 2. منع سباق DiffUtil
- أضفت `updateGeneration` داخل `ChannelsAdapter`.
- لو وصل تحديث قديم بعد تحديث جديد، يتم تجاهله.
- للقوائم الكبيرة أكثر من 5000 عنصر، يتم استعمال `notifyDataSetChanged()` مباشرة لتجنب تأخر `DiffUtil`.

### 3. تسريع جلب Xtream
- حولت `tryXtreamApi()` إلى suspend.
- تحميل خرائط التصنيفات الثلاث صار بالتوازي.
- تحميل live/vod/series streams صار بالتوازي.

### 4. التخزين المحلي local-first
- `HomeFragment` و `ChannelListActivity` الآن يقرؤون `ChannelCache` أولاً.
- إذا الكاش موجود، يفتح بسرعة بدون تحميل من النت.
- إذا الكاش غير موجود، يجلب من الرابط ثم يخزنه في `context.filesDir`.
- `assets/channels.json` صار fallback فقط إذا الحساب لا يملك `m3uUrl`.

### 5. إصلاح كاش المسلسلات
- `ChannelCache.load()` صار يتحقق من live/vod/series معاً.
- استعملت `split("\t", limit = 5)` لتفادي تقطيع خاطئ زائد.

## الملفات المعدلة

- `app/src/main/java/com/latchi/iptv/provider/ChannelsProvider.kt`
- `app/src/main/java/com/latchi/iptv/adapter/ChannelsAdapter.kt`
- `app/src/main/java/com/latchi/iptv/screens/HomeFragment.kt`
- `app/src/main/java/com/latchi/iptv/screens/ChannelListActivity.kt`
- `app/src/main/java/com/latchi/iptv/utils/ChannelCache.kt`

## ملاحظة مهمة حول صلاحية التخزين

التخزين الحالي في `context.filesDir` هو تخزين داخلي للتطبيق، ويعمل على الهاتف والتلفاز بدون طلب صلاحية Storage. هذا أفضل من طلب صلاحيات التخزين الخارجية، خصوصاً على Android TV و Android 13+ حيث تغير نظام الصلاحيات.

يعني الهاتف/التلفاز يصبح عنده قاعدة بيانات محلية للقنوات، لكن تشغيل الستريم نفسه يبقى يحتاج إنترنت لأن روابط القنوات خارجية.

## ملاحظة أمنية

يوجد ملف `assets/channels.json` كبير داخل التطبيق يحتوي على روابط تشغيل جاهزة. إذا هذه الروابط خاصة أو فيها بيانات اشتراك، الأفضل حذفها من المستودع العام وتدوير/تغيير بيانات الاشتراك لأنها أصبحت مكشوفة في APK والمستودع.

## حالة البناء

حاولت تشغيل:

```bash
./gradlew assembleDebug --no-daemon
```

لكن البناء فشل بسبب مشكلة TLS/handshake في بيئة التحميل عند الوصول إلى Maven، وليس بسبب كود Kotlin المعدل. الكود تم فحصه ومراجعته من ناحية التركيب والمنطق.

---

## تحديث إضافي: Google Sheet كسيرفر ديناميكي

بعد مراجعة ملفات التفعيل وجدت أن التطبيق كان يستعمل Google Apps Script للتحقق من الكود، لكن كان عنده مشكلتين:

1. `USE_LOCKED_PROVIDER_AFTER_GOOGLE_CODE = true` داخل `ActivationConfig.kt`، وهذا يعني أن التطبيق يتحقق من الكود في Google Sheet لكنه لا يستعمل رابط `playlist_url` الراجع من Google Sheet، بل يستعمل رابطاً ثابتاً داخل التطبيق.
2. `SplashActivity` كان إذا الحساب verified يدخل مباشرة إلى `MainActivity` ولا يعيد التحقق من Google Sheet، لذلك تغيير رابط السيرفر في Google Sheet لا يصل إلى التطبيق.

تم تعديل التالي:

- جعل `USE_LOCKED_PROVIDER_AFTER_GOOGLE_CODE = false`.
- إضافة parser يقبل أعمدة متعددة للرابط: `playlist_url`, `m3u_url`, `m3u`, `url`, `playlist`, `link`.
- إضافة دعم Xtream كأعمدة منفصلة: `server`, `username`, `password`.
- توحيد التحقق في `ActivationValidator.validateCode()` حتى شاشة إدخال الكود و شاشة التحقق يستعملان نفس المنطق.
- جعل `SplashActivity` يمر على `VerificationActivity` بصمت عند كل تشغيل للحسابات المفعلة بالكود.
- إذا تغير `playlist_url` من Google Sheet، يتم حفظ الرابط الجديد ومسح كاش القنوات القديم تلقائياً.

تمت إضافة ملفات مساعدة:

- `GOOGLE_SHEET_SETUP_AR.md`
- `google_apps_script_activation.gs`


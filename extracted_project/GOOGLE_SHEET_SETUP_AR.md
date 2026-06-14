# ماذا تدير الآن؟ ربط التطبيقين مع Google Sheet

## النتيجة المطلوبة

بعد التعديل عندك زوج تطبيقات يخدمو على نفس Google Apps Script:

1. **تطبيق المشاهدة Latchi IPTV**
   - المستخدم يدخل كود.
   - التطبيق يسأل Google Sheet.
   - يرجع `playlist_url` ويشغل القنوات.
   - عند كل تشغيل يعاود يتحقق بصمت، وإذا تبدل السيرفر يمسح الكاش القديم ويجيب الجديد.

2. **تطبيق التحكم LTC IPTV Dashboard**
   - تضيف كود جديد.
   - تعمم سيرفر واحد على كل المستخدمين.
   - تشوف المستخدمين والسيرفرات.
   - تعدل، توقف، تجدد، تحذف.

## جدول Google Sheet تاعك مدعوم

الصورة تاعك فيها:

```text
code | status | max_devices | devices | expires_at | playlist_url | name
```

هذا هو الشكل الصحيح.

## أهم سبب يخلي كود ما يخدمش

إذا `max_devices = 1` وعمود `devices` فيه جهاز قديم، الكود لن يشتغل في هاتف/تلفاز آخر.

الحل:

- امسح خلية `devices` للكود الذي تريد تجربته
- أو زيد `max_devices`

مثال:

```text
code: 123321
max_devices: 1
devices: 7473a1895dff...
```

هذا راه محجوز لجهاز واحد.

## كيف تدير سيرفر واحد لكل المستخدمين؟

عندك زوج طرق:

### الطريقة السهلة من تطبيق Dashboard

1. افتح تطبيق التحكم.
2. أدخل PIN.
3. في خانة `رابط السيرفر المعتمد Google Script` حط رابط Apps Script `/exec`.
4. في خانة `سيرفر واحد لكل المستخدمين` ألصق رابط M3U:

```text
http://server/get.php?username=USER&password=PASS&type=m3u_plus&output=ts
```

5. اضغط:

```text
تعميم السيرفر على كل المستخدمين
```

السكريبت سيقوم بـ:

- وضع الرابط في `Config/default_playlist_url`
- تحديث `playlist_url` لكل الأكواد الموجودة
- أي مستخدم يفتح تطبيق المشاهدة يأخذ الرابط الجديد

### الطريقة اليدوية من Google Sheet

اعمل Sheet اسمه:

```text
Config
```

وفيه:

```text
key | value
default_playlist_url | http://server/get.php?username=USER&password=PASS&type=m3u_plus&output=ts
```

ملاحظة: إذا سطر المستخدم عنده `playlist_url` خاص، هذا له أولوية. إذا حبيت كل المستخدمين يستعملو الرابط العام، خليه نفس الرابط أو امسح روابطهم الخاصة.

## لازم تحديث السكريبت؟

نعم، لكن ليس لأنك لم تحدثه سابقاً؛ السبب أن السكريبت السابق كان يخدم التفعيل فقط وما كانش يدعم أوامر Dashboard مثل:

```text
add_code
update_master_url
get_all_users
update_status
edit_user
renew_user
delete_user
```

السكريبت الجديد v3 يخدم التطبيقين معاً.

## خطوات تحديث Google Apps Script

1. افتح Google Sheet.
2. Extensions > Apps Script.
3. امسح الكود القديم.
4. انسخ كامل محتوى الملف:

```text
latchi-iptv-build/google_apps_script_activation.gs
```

5. اضغط Save.
6. Deploy > Manage deployments.
7. Edit.
8. Version: New version.
9. Deploy.
10. استعمل رابط `/exec` نفسه أو الرابط الجديد في التطبيقين.

مهم جداً:

- تغيير بيانات Google Sheet لا يحتاج Deploy.
- تغيير كود Apps Script يحتاج New version ثم Deploy.

## اختبارات ضرورية

### اختبار التفعيل

```text
https://script.google.com/macros/s/XXXX/exec?code=123400&device_id=test123
```

لازم يرجع:

```json
{
  "success": true,
  "message": "OK",
  "playlist_url": "http://...",
  "expires_at": "2026-10-30"
}
```

### اختبار Dashboard - كل المستخدمين

```text
https://script.google.com/macros/s/XXXX/exec?action=get_all_users&secret=LatchiAdmin2026
```

لازم يرجع:

```json
{
  "success": true,
  "users": []
}
```

### اختبار تعميم سيرفر واحد

```text
https://script.google.com/macros/s/XXXX/exec?action=update_master_url&secret=LatchiAdmin2026&master_url=http://server/get.php?username=u&password=p&type=m3u_plus&output=ts
```

يرجع:

```json
{
  "success": true,
  "message": "Master URL updated for all users",
  "updated_users": 10
}
```

## ملاحظة حول التاريخ

السكريبت يقبل التاريخ كما في صورتك:

```text
26-12-2026
```

ويحوّله إلى:

```text
2026-12-26
```

## الملفات الجاهزة

- سكريبت Google الكامل:

```text
google_apps_script_activation.gs
```

- مشروع تطبيق المشاهدة المعدل:

```text
viewer_app_IPTVmine_fixed
```

- مشروع تطبيق التحكم المعدل:

```text
admin_dashboard_fixed
```

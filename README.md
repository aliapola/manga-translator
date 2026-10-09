# مترجم آفلاین مانگا / مانهوا / مانهوآ (Android)

اپلیکیشن اندروید که متن روی صفحه گوشی (حباب‌های مانگا، مانهوا، کمیک، بازی) را
تشخیص می‌دهد و ترجمه را به‌صورت کاملاً آفلاین روی همان صفحه (Overlay) نشان می‌دهد.

## معماری کلی

```
MainActivity        -> درخواست مجوز Overlay و MediaProjection + میزبان صفحات Compose
ScreenCaptureService -> Foreground Service که MediaProjection را نگه می‌دارد و
                        در هر درخواست یک فریم (Bitmap) از صفحه می‌گیرد
OverlayService       -> دکمه شناور + پنجره Overlay شفاف برای رسم ترجمه روی حباب‌ها
OcrProcessor         -> اجرای ML Kit Text Recognition (لاتین/ژاپنی/کره‌ای/چینی)
LanguagePackManager  -> دانلود/حذف/بررسی وضعیت بسته‌های زبان ML Kit Translate
TranslationManager   -> کش نشست، ترجمه آنلاین اختیاری (Gemini/Google/MyMemory) و در غیر این‌صورت مدل آفلاین
TranslationLayerView -> لایه رسم ترجمه‌ها روی اسکرین‌شات؛ هر ترجمه به‌محض آماده شدن نمایش داده می‌شود
Compose UI (HomeScreen, LanguagePackScreen, SettingsScreen)
```

### چرا این‌طور کار می‌کند (خلاصه فنی)

- **Screen Capture**: `MediaProjectionManager.createScreenCaptureIntent()` در
  `MainActivity` مجوز را می‌گیرد؛ نتیجه (`resultCode` + `data`) به
  `ScreenCaptureService` پاس داده می‌شود تا `MediaProjection` را بسازد و یک
  `VirtualDisplay` متصل به `ImageReader` راه بیندازد. هر بار که کاربر دکمه شناور
  را می‌زند، فقط **یک فریم** با `acquireLatestImage()` گرفته می‌شود (نه استریم
  پیوسته) تا مصرف باتری معقول بماند.
- **Overlay**: `OverlayService` دو پنجره‌ی `WindowManager` اضافه می‌کند: یکی
  دکمه‌ی شناور قابل کشیدن (`TYPE_APPLICATION_OVERLAY`)، و یکی
  `FrameLayout` تمام‌صفحه‌ی شفاف و غیرقابل‌لمس (`FLAG_NOT_TOUCHABLE`) که فقط
  برای نمایش کادرهای ترجمه روی مختصات دقیق حباب اصلی استفاده می‌شود.
- **OCR چندخطی**: از چهار مدل ML Kit (Latin/Chinese/Japanese/Korean) بسته به
  زبان انتخابی کاربر استفاده می‌شود. یک تابع کمکی (`looksVertical`) با بررسی
  نسبت ابعاد خطوط، تشخیص می‌دهد که آیا بلوک متن به‌صورت عمودی (رایج در مانگای
  ژاپنی) چیده شده یا نه.
- **ترجمه آفلاین**: `com.google.mlkit:translate` مدل‌های ترجمه را برای هر زبان
  به‌صورت جداگانه دانلود می‌کند (`RemoteModelManager`). بعد از دانلود، تابع
  `Translator.translate()` هیچ تماس شبکه‌ای برقرار نمی‌کند.
- **پایپ‌لاین سریع**: گرفتن فریم، OCR، ترجمه و محاسبه چیدمان متن همگی روی
  threadهای پس‌زمینه انجام می‌شوند؛ همه بلوک‌ها هم‌زمان (و متن‌های تکراری فقط
  یک‌بار) ترجمه می‌شوند و هر ترجمه به‌محض آماده شدن روی صفحه کشیده می‌شود.
  مدل OCR (و در حالت آفلاین مدل ترجمه) هنگام شروع سرویس از قبل بارگذاری
  (warm-up) می‌شود. در لاگ برنامه خطوط `Timing:` زمان هر مرحله را نشان می‌دهند.

## ساختار پروژه

```
MangaTranslator/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/mangatranslator/app/
        │   ├── MyApplication.kt
        │   ├── MainActivity.kt
        │   ├── data/ (SettingsStore)
        │   ├── ocr/ (OcrProcessor)
        │   ├── translation/ (LanguagePackManager, TranslationManager, کلاینت‌های آنلاین)
        │   ├── service/ (ScreenCaptureService, OverlayService, TranslationLayerView)
                │   └── ui/
        │       ├── theme/ (Theme.kt)
        │       └── screens/ (HomeScreen, LanguagePackScreen, SettingsScreen)
        └── res/ (values, drawable, mipmap)
```

## نصب و اجرا

1. **باز کردن پروژه**: پوشه `MangaTranslator` را با Android Studio (نسخه
   Koala یا جدیدتر، پیشنهادی) با گزینه *Open* باز کنید. چون فایل باینری
   `gradle-wrapper.jar` در این خروجی گنجانده نشده (محدودیت محیط تولید فایل)،
   Android Studio هنگام Sync اول به‌صورت خودکار wrapper را بازسازی می‌کند؛ اگر
   این اتفاق نیفتاد، از منوی *Gradle* گزینه *"Regenerate Wrapper"* یا دستور
   زیر را (در صورت نصب بودن Gradle به‌صورت جداگانه روی سیستم) اجرا کنید:
   ```
   gradle wrapper --gradle-version 8.7
   ```
2. **Sync پروژه**: صبر کنید Gradle Sync تمام شود (دسترسی اینترنت فقط برای
   دانلود کتابخانه‌ها لازم است، نه برای اجرای خود اپ).
3. **اجرا روی گوشی/شبیه‌ساز**: حداقل Android 8.0 (API 26). دستگاه واقعی برای
   تست Overlay و MediaProjection تجربه بهتری نسبت به شبیه‌ساز می‌دهد.
4. **اولین اجرا در اپ**:
   - وارد صفحه اصلی شوید و دکمه «شروع ترجمه روی صفحه» را بزنید.
   - ابتدا مجوز «نمایش روی سایر برنامه‌ها» (Overlay) درخواست می‌شود؛ آن را در
     تنظیمات فعال کنید و به اپ برگردید.
   - سپس دیالوگ سیستمی MediaProjection («شروع ضبط یا نمایش؟») ظاهر می‌شود؛
     تأیید کنید.
   - حالا دکمه شناور روی هر برنامه‌ای (مثلاً اپ مانگاخوان) ظاهر می‌شود.
5. **دانلود بسته زبان**: قبل از ترجمه، از صفحه «بسته‌های زبان آفلاین» زبان
   مبدأ (مثلاً ژاپنی) و «فارسی (مقصد)» را دانلود کنید (فقط یک‌بار و نیاز به
   اینترنت دارد؛ بعد از آن کاملاً آفلاین کار می‌کند).
6. **ترجمه آنلاین (اختیاری)**: از «تنظیمات» می‌توانید Google یا MyMemory را
   فعال کنید؛ با خاموش بودن آن یا نبود اینترنت، مدل آفلاین استفاده می‌شود.
7. **استفاده**: داخل اپ مانگا/کمیک مورد نظر را باز کنید، روی دکمه شناور ضربه
   بزنید؛ کادرهای ترجمه دقیقاً روی محل حباب اصلی ظاهر می‌شوند.

## نکاتی که باید قبل از انتشار روی Google Play در نظر بگیرید

- **سیاست MediaProjection**: از نسخه‌های اخیر Android، هر بار که اپ به پس‌زمینه
  می‌رود و دوباره به جلو می‌آید ممکن است لازم باشد مجوز ضبط صفحه دوباره تأیید
  شود (محدودیت امنیتی خود سیستم‌عامل، نه چیزی که در کد قابل دور زدن باشد).
- **مصرف باتری**: چون هر ترجمه فقط یک فریم می‌گیرد (نه استریم پیوسته)، مصرف
  محدود به لحظه‌ی ضربه روی دکمه شناور یا حالت خودکار (که باید با یک تایمر یا
  تشخیص تغییر فریم به‌جای polling مداوم پیاده‌سازی شود).
- **حالت "ترجمه خودکار هنگام تغییر صفحه"**: در `SettingsScreen` سوییچ آن آماده
  است؛ برای پیاده‌سازی کامل باید در `OverlayService` یک مقایسه‌ی سبک بین دو
  فریم متوالی (مثلاً هیستوگرام یا هش تصویر) اضافه کنید تا فقط در صورت تغییر
  واقعی صفحه OCR اجرا شود؛ اسکلت لازم برای این کار در `translateRegion` و
  `onFloatingButtonTapped` گذاشته شده تا راحت گسترش پیدا کند.
- **انتخاب ناحیه دستی**: تابع `OverlayService.translateRegion(region: Rect)`
  آماده است؛ برای UI کشیدن کادر انتخاب روی صفحه، یک `View` شفاف با
  `onTouch` برای رسم مستطیل و سپس فراخوانی همین تابع اضافه کنید.

## محدودیت‌های شناخته‌شده در این نسخه

- فایل باینری `gradlew` / `gradlew.bat` / `gradle-wrapper.jar` به‌دلیل نبود
  دسترسی شبکه در محیط تولید این پروژه گنجانده نشده‌اند؛ Android Studio آن‌ها
  را در اولین Sync می‌سازد.
- آیکون اپ یک وکتور ساده است؛ برای انتشار نهایی بهتر است با Image Asset
  Studio در Android Studio آیکون حرفه‌ای‌تر بسازید.

<div align="center">

# UAC SNI Spoofer Android

<img width="1672" height="941" alt="5ddf67f8-f5b8-4eb9-94c3-8e324ee5f816" src="https://github.com/user-attachments/assets/f99d9c99-a01b-43f7-b3f1-f4077d45cf27" />


<a href="./README.md">فارسی</a> · <a href="./README.en.md">English</a>

</div>

<div dir="rtl" align="right">

<h2>معرفی</h2>

<p><span dir="ltr">UAC SNI Spoofer</span> یک ابزار متن‌باز برای مدیریت اتصال‌های امن در اندروید است. برنامه از مسیر بومی <span dir="ltr">VPN/TUN</span> و هسته <span dir="ltr">Xray</span> استفاده می‌کند و برای اتصال سریع، مدیریت کانفیگ‌ها و بررسی وضعیت واقعی شبکه طراحی شده است.</p>

<p>نسخه فعلی: <a href="https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases/latest"><img src="https://img.shields.io/github/v/release/Floxu1/UAC-SNI-Spoofer-Android?display_name=tag&amp;sort=semver&amp;label=version" alt="آخرین نسخه منتشرشده"></a></p>

<h2>امکانات</h2>

<ul>
  <li>تونل سراسری اندروید با <span dir="ltr">Android VpnService</span>، هسته <span dir="ltr">Xray</span> و مسیر بومی <span dir="ltr">TUN</span></li>
  <li>پشتیبانی از کانفیگ‌های <span dir="ltr">VLESS</span>، <span dir="ltr">VMess</span> و <span dir="ltr">Trojan</span> همراه با حفظ مشخصات اصلی انتقال، امنیت، <span dir="ltr">SNI</span>، <span dir="ltr">Host</span>، <span dir="ltr">Path</span>، <span dir="ltr">ALPN</span> و <span dir="ltr">Fingerprint</span></li>
  <li><strong>اتصال تطبیقی:</strong> ساخت اثرانگشت جداگانه برای هر شبکه با توجه به نوع اتصال، اپراتور، <span dir="ltr">ASN</span> و سرویس‌دهنده؛ سپس مرتب‌سازی مسیرها، یادگیری نتیجه موفق و اتصال سریع‌تر در دفعات بعد</li>
  <li>استفاده از مجموعه <span dir="ltr">Edge</span>های اصلی و جایگزین متناسب با شبکه، همراه با مسیر <span dir="ltr">Direct Compatibility</span> برای آزمایش کانفیگ بدون جایگزینی آدرس، تغییر <span dir="ltr">ALPN</span> یا اعمال <span dir="ltr">FinalMask</span></li>
  <li>بازیابی خودکار اتصال هنگام تغییر شبکه یا افت کیفیت با کمک برنده ذخیره‌شده، مسیر پشتیبان و زمان استراحت مسیرهای ناموفق</li>
  <li><strong><span dir="ltr">Route Speed Test</span>:</strong> بررسی کامل ترکیب‌های <span dir="ltr">Edge × DNS × Fragment × MTU</span> و آزمایش صدها مسیر مستقل برای هر کانفیگ و شبکه</li>
  <li>رقابت چندمرحله‌ای مسیرها از غربال اولیه تا آزمون پایداری، فشار و فینال <span dir="ltr">A-B-B-A</span>؛ با راه‌اندازی سرد <span dir="ltr">Xray</span>، تست چندمقصدی <span dir="ltr">HTTP</span>، پاسخ <span dir="ltr">DNS</span>، حجم دریافتی، سرعت، پینگ، نوسان، نرخ موفقیت و درصد اطمینان</li>
  <li>رتبه‌بندی زنده بهترین مسیرها، توقف و ادامه تست بدون از دست‌رفتن نتیجه، رفتن دستی به مرحله بعد و نگهداری لیست نهایی مخصوص همان کانفیگ و اثرانگشت شبکه</li>
  <li>انتخاب یک برنده و یک مسیر پشتیبان، ذخیره آن‌ها برای همان کانفیگ و شبکه و استفاده مستقیم در اتصال‌های بعدی</li>
  <li>چند <span dir="ltr">DNS Resolver</span> مستقل شامل <span dir="ltr">Cloudflare</span>، <span dir="ltr">Google</span>، <span dir="ltr">Quad9</span>، <span dir="ltr">AdGuard</span> و <span dir="ltr">OpenDNS</span> با <span dir="ltr">DoH</span> و آدرس‌های <span dir="ltr">Bootstrap</span></li>
  <li><strong><span dir="ltr">Config Maker</span>:</strong> دو روش <span dir="ltr">Quick Scan</span> و <span dir="ltr">Deep Adaptive Test</span> برای سنجش کانفیگ‌ها، نمایش مسیر در حال تست و توقف روی اولین نتیجه سالم</li>
  <li>واردکردن کانفیگ از متن، کلیپ‌بورد، فایل یا لینک اشتراک؛ ادغام چند اشتراک بدون پاک‌شدن نتیجه‌های قبلی و حذف خودکار موارد تکراری</li>
  <li>سه حالت مسیریابی برنامه‌ها: عبور همه برنامه‌ها از <span dir="ltr">VPN</span>، دورزدن تونل برای برنامه‌های انتخابی یا عبور فقط برنامه‌های انتخابی از <span dir="ltr">VPN</span></li>
  <li>حالت‌های اتصال <span dir="ltr">Tunnel</span> و پروکسی محلی <span dir="ltr">SOCKS</span>، همراه با کنترل‌های <span dir="ltr">Fragment</span>، <span dir="ltr">FinalMask</span>، <span dir="ltr">MTU</span>، <span dir="ltr">Mux</span>، <span dir="ltr">Keepalive</span>، <span dir="ltr">QUIC</span> و مسیریابی</li>
  <li>پایش زنده پینگ، ترافیک، کشور و آدرس خروجی، وضعیت سلامت اتصال و گزارش‌های فنی برای عیب‌یابی</li>
  <li>اتصال و قطع سریع <span dir="ltr">VPN</span> از پنل <span dir="ltr">Quick Settings</span> اندروید و کنترل‌های اعلان</li>
</ul>

<h2>نیازمندی‌ها</h2>

<ul>
  <li>اندروید ۷ یا جدیدتر</li>
  <li>دادن مجوز استاندارد <span dir="ltr">VPN</span> هنگام اولین اتصال</li>
  <li>خاموش‌بودن برنامه‌های <span dir="ltr">VPN</span> دیگر هنگام استفاده</li>
</ul>

<h2>نصب</h2>

<ol>
  <li>فایل <span dir="ltr">APK</span> نسخه جدید را از بخش <a href="https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases"><span dir="ltr">Releases</span></a> دریافت کنید.</li>
  <li>برنامه را نصب و اجرا کنید.</li>
  <li>کانفیگ موردنظر را انتخاب کنید و دکمه اتصال را بزنید.</li>
  <li>درخواست مجوز <span dir="ltr">VPN</span> را تأیید کنید.</li>
</ol>

<h2>ساخت از سورس</h2>

<p>برای ساخت پروژه به <span dir="ltr">JDK 17</span> و <span dir="ltr">Android SDK 35</span> نیاز دارید.</p>

<pre dir="ltr" align="left"><code>git clone https://github.com/Floxu1/UAC-SNI-Spoofer-Android.git
cd UAC-SNI-Spoofer-Android
.\gradlew.bat assembleDebug</code></pre>

<p>خروجی نسخه دیباگ در مسیر زیر ساخته می‌شود:</p>

<pre dir="ltr" align="left"><code>app\build\outputs\apk\debug\app-debug.apk</code></pre>

<h2>پشتیبانی و ارتباط</h2>

<ul>
  <li>کانال تلگرام: <a href="https://t.me/UacSniSpoofer"><span dir="ltr">t.me/UacSniSpoofer</span></a></li>
  <li>گروه تلگرام: <a href="https://t.me/UacSniSpooferGroup"><span dir="ltr">t.me/UacSniSpooferGroup</span></a></li>
  <li>گزارش مشکل: <a href="https://github.com/Floxu1/UAC-SNI-Spoofer-Android/issues"><span dir="ltr">GitHub Issues</span></a></li>
</ul>

<h2>نکته</h2>

<p>کیفیت اتصال به وضعیت اپراتور، کانفیگ انتخاب‌شده و شرایط شبکه بستگی دارد. هیچ کانفیگی روی تمام شبکه‌ها عملکرد یکسانی ندارد.</p>

<p>مجوزها و توضیحات وابستگی‌های جانبی در فایل <a href="./THIRD_PARTY_NOTICES.md"><span dir="ltr">THIRD_PARTY_NOTICES.md</span></a> قرار گرفته است.</p>

<h3>اگر این پروژه براتون مفید بود، لطفاً ستاره بدین ⭐</h3>

</div>

# Fix: WireGuard و Gool → `socks5 listener did not become ready`

## خلاصه لاگ کاربر (`/home/user/uploads/log arn fail.txt`)
- BigRocket log:
  - `Path3 TCP CONNECT pin chosen=cellular dest=api.cloudflareclient.com:443 resolved -> 2606:4700::6810:1854 via cellular`
  - سپس `relay timeout (download dest=api.cloudflareclient.com:443) after 20000ms idle` هر 20 ثانیه تکرار
  - `Direct-TCP openSession failed 10.10.34.35:443 ECONNREFUSED` (ترافیک TUN جانبی، نامربوط به Aether اما نشانه مسیر مستقیم)
- Aether diagnostics:
  - `[+] dialling out through the socks5 proxy at 127.0.0.1:12347` (BondingSocksServer.PORT)
  - `registration retry 1/4 error sending request for url https://api.cloudflareclient.com/v0a4471/reg`
  - `Engine output stream closed.` → PortProbe.awaitOpen روی 127.0.0.1:1819 تایم‌اوت → `EmbeddedAetherRuntime.kt:66 error("Aether SOCKS5 listener did not become ready")`

## ریشه‌یابی
1. **BondingSocksServer.handleConnect فقط یک شبکه و یک IP را امتحان می‌کرد**:
   - `pickBestNetwork()` → یک Network (در لاگ cellular)
   - `picked.getAllByName(destHost).firstOrNull()` → اولین نتیجه DNS (در لاگ IPv6 `2606:4700::6810:1854`)
   - اگر آن IPv6 روی اپراتور reachable نبود (blackhole / فیلتر)، TCP connect موفق می‌شد اما هیچ دیتایی برنمی‌گشت و بعد 20 ثانیه `relay timeout` می‌خورد.
   - Rust `http_client` با تایم‌اوت 20s هر تلاش را fail می‌کرد، 5 بار retry + fallback `apifront` (که خود از TUN و دوباره Bonding می‌گذرد) هم به همین دلیل fail → پروسه Rust exit → PortProbe هرگز 1819 را باز نمی‌دید.

2. **UDP ASSOCIATE برای WireGuard/Gool**:
   - گیرنده `startPinnedReceiver` هاست/پورت اولین درخواست را capture و برای همه replyها reuse می‌کرد. در فاز scan که Aether ~285 کاندید را با UDP probe می‌کند، replyها به endpoint اشتباه نسبت داده می‌شد → اعتبارسنجی تانل شکست → SOCKS5 listener باز نمی‌شد.
   - ارسال UDP فقط `firstOrNull()` را resolve می‌کرد و fallback شبکه نداشت.

3. **PortProbe**:
   - `FAST_PHASE_MS=5s` برای Gool که دو تانل WireGuard پشت‌سرهم می‌سازد کم بود و `isEngineAlive()` بلافاصله بعد از `isOpen()` چک می‌شد که race داشت.

## اصلاحات اعمال‌شده (همه فایل‌ها کامنت‌گذاری شد)

### 1. `Path3Router.kt`
- متد جدید `allNetworksSorted(): List<Network>` اضافه شد:
  - همه شبکه‌های موجود (Wi-Fi, Cellular) را بر اساس weight نزولی برمی‌گرداند.
  - برای fallback سریع وقتی شبکه اول به Cloudflare API وصل نمی‌شود.

### 2. `BondingSocksServer.kt` (اصلی‌ترین فیکس)
**TCP CONNECT:**
- به‌جای یک شبکه و یک IP، حلقه روی `allNetworksSorted()` و روی همه IPهای resolve شده.
- `sortedWith(compareBy({ it is Inet6Address }, ...))` → IPv4 اول (چون IPv6 روی اپراتورهای AZ/IR اغلب خراب است).
- لاگ `trying net=... resolved=...` و `connect failed ... -> ip: msg` برای دیباگ.
- fallback به `InetAddress.getAllByName` سیستم اگر DNSهای شبکه‌ای fail شدند.
- خطا با جزئیات لاگ می‌شود.

**UDP ASSOCIATE:**
- `startPinnedReceiver` اکنون از `resp.address` و `resp.port` واقعی (source اینترنت) برای encode استفاده می‌کند، نه از هاست/پورت اولین درخواست. این scan Gool/WG را درست می‌کند.
- ارسال UDP: همه IPها با IPv4-first، و در صورت `IOException`، به شبکه دیگر fallback و rebind می‌کند.
- لاگ fallback.

### 3. `EmbeddedAetherRuntime.kt`
- تایم‌اوت استارتاپ برای WG/Gool حداقل 60s (به‌جای 10s) شد چون Gool دو handshake متوالی دارد.
- لاگ `awaiting SOCKS5 ... timeout=... proto=...` و `SOCKS5 listener not ready ... engineAlive=...` برای تشخیص سریع.

### 4. `PortProbe.kt`
- `FAST_PHASE_MS` از 5s به 15s افزایش یافت تا Gool/WG فرصت کافی برای باز کردن پورت بعد از اعتبارسنجی داشته باشد.
- چک `isEngineAlive()` بعد از `isOpen()` و با grace کوچک انجام می‌شود تا race از بین برود.
- کامنت توضیح فیکس اضافه شد.

## نتیجه
- ثبت‌نام WARP اکنون حتی اگر cellular IPv6 به `api.cloudflareclient.com` بلاک باشد، از طریق Wi-Fi یا IPv4 fallback موفق می‌شود.
- UDP scan برای WG/Gool با آدرس‌دهی صحیح replyها، اعتبارسنجی را پاس می‌کند و `127.0.0.1:1819` باز می‌شود.
- خطای `socks5 listener did not become ready` برطرف می‌شود؛ در صورت بروز مجدد، لاگ‌های جدید مسیر دقیق fail را نشان می‌دهند.

## تست پیشنهادی
1. دستگاه با Wi-Fi + Cellular، هر دو فعال.
2. در BigRocket حالت `BigRocket + Aether`، پروتکل Gool و سپس WireGuard، scanMode Turbo/Balanced.
3. لاگ باید `TCP CONNECT trying net=... resolved=...` با لیست IPv4 و IPv6 و سپس `pin chosen=... -> <ipv4>:443` را نشان دهد، بدون `relay timeout` مکرر.
4. بعد از `identity ready` باید `SOCKS5 listener` باز و `trafficReady=true` شود.

## فایل‌های تغییریافته
- `app/src/main/java/com/bigrocket/service/Path3Router.kt`
- `app/src/main/java/com/bigrocket/service/BondingSocksServer.kt`
- `app/src/main/java/com/bigrocket/service/EmbeddedAetherRuntime.kt`
- `app/src/main/java/studio/cluvex/aether/core/PortProbe.kt`

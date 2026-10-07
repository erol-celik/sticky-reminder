# Sticky Görev Uygulaması — Gereksinimler ve Plan

Windows'ta sticky tarzı küçük bir pencere, Android'de büyük bir ana ekran widget'ı; ikisi aynı Google Drive hesabındaki gizli bir uygulama klasörü üzerinden senkronlanır. Bildirim yok, Drive dışında hiçbir sunucuyla iletişim yok.

## Verilen kararlar

| Konu | Karar | Not |
| --- | --- | --- |
| Android | Native Kotlin + büyük Jetpack Glance widget | Ana ekrana sabit, uygulamadaki ekleme/silme widget'a yansır |
| Windows | Tauri | Tek sanal masaüstünde sürekli açık duran küçük pencere |
| Senkron | Google Drive gizli uygulama klasörü (drive.appdata), tek JSON dosya | Ücretsiz, ek sunucu yok; her cihazda bir kez Google girişi |
| Senkron tetikleme | Hem elle hem otomatik | Elle "Kaydet"; otomatik: uygulama açılırken, internet gelince ve değişiklikten kısa süre sonra |
| Bildirim | Yok | Deadline yalnızca görüntülenir ve sıralamada kullanılır |
| Tekrarlayan görev | Deadline'sız, listenin en üstünde sabit | Sıfırlanma kuralı: açık soru |
| v1 kapsamı | Not + yapıldı işareti, deadline, renk ve etiket, tekrarlayan görev | |

Neden Drive: ücretsiz, Google hesabı telefonda zaten hazır, uygulama yalnızca kendi gizli klasörüne yazar (Drive'da görünmez, diğer dosyalara erişmez). Dropbox veya OneDrive de aynı mantıkla çalışırdı, ama Drive en az sürtünmeli yol.

## Fonksiyonel gereksinimler

Her madde v1'de test edilebilir olmalı; öncelik: **Z** = zorunlu, **İ** = istenirse.

| No | Gereksinim | Öncelik |
| --- | --- | --- |
| F1 | Tek satırla hızlı görev ekleme (Windows penceresinden, Android'de widget ve uygulamadan) | Z |
| F2 | Görev, kullanıcı silene kadar listede kalır; "yapıldı" işaretlenebilir ve silinebilir | Z |
| F3 | İsteğe bağlı deadline (tarih + saat); listede gösterilir, deadline'a göre sıralanır, geçmiş olanlar vurgulanır. Bildirim yok. Tekrarlayan görevlerde deadline yoktur | Z |
| F4 | Not başına renk ve serbest etiketler; etikete göre filtre | Z |
| F5 | Çevrimdışı tam çalışma: internet yokken ekleme, düzenleme, silme çalışır | Z |
| F6 | Her cihazda bir kez Google ile giriş; sonrasında yeniden giriş istenmez | Z |
| F7 | Senkron hem elle hem otomatik: "Kaydet" düğmesi gönderir ve getirir; ayrıca uygulama açılırken, internet geri gelince ve değişiklikten kısa süre sonra otomatik çalışır. Bekleyen değişiklik sayısı ve sonuç (başarılı/hata) görünür | Z |
| F8 | Android widget: büyük, ana ekrana sabit, açık görevleri listeler; widget'tan yapıldı/sil/ekle; uygulamada yapılan değişiklik widget'a yansır | Z |
| F9 | Windows penceresi: küçük, sticky görünümlü, sürekli açık; tek sanal masaüstünde durur (tüm masaüstlerinde gösterme kapalı); konum ve boyutu hatırlar; üzerinde "Kaydet" düğmesi | Z |
| F10 | Tekrarlayan görev: deadline'sızdır, listenin en üstünde ayrı bir bölümde sabit durur; tekrar ve sıfırlanma kuralı açık soruda | Z |
| F11 | Windows'ta oturum açılışında otomatik başlama | İ |

## Fonksiyonel olmayan gereksinimler

- **Boyut:** Windows kurulum dosyası mümkün olduğunca küçük (Tauri ile tek haneli MB hedeflenir, ölçümle doğrulanacak).
- **Bellek:** Pencere gün boyu açık kalacağı için boştayken çok az kaynak kullanmalı.
- **Çevrimdışı-öncelikli:** Veri önce cihazdaki yerel veritabanına yazılır; ağ hatası kullanıcıya engel olmaz.
- **Gizlilik:** Yalnızca Google Drive ile iletişim; reklam, analitik, başka sunucu yok.
- **Maliyet:** Ücretsiz; görev listesi küçük bir JSON dosyası olduğundan Drive kotasını pratikte etkilemez.
- **Bakım:** Tek kişinin sürdürebileceği kadar basit; en az bağımlılık.

## Mimari ve senkron tasarımı

Her cihazda yerel veritabanı asıl kaynaktır; Drive yalnızca cihazlar arasındaki köprüdür. Görevler, uygulamanın gizli Drive klasöründe (appDataFolder) tek bir JSON dosyası olarak durur.

1. İlk kullanımda her cihazda bir kez Google ile giriş yapılır. İzin yalnızca uygulamanın gizli klasörü içindir; Drive'daki diğer dosyalara erişilmez.
2. Ekleme, silme, yapıldı işaretleri önce yerelde kaydedilir. İnternet yokken de çalışır; bekleyen değişiklik sayısı ekranda görünür.
3. "Kaydet"e basınca (veya otomatik tetikleyicilerde) uygulama Drive'daki dosyayı indirir, yerel değişikliklerle birleştirir ve sonucu geri yükler.
4. Birleştirme görev bazındadır: en son güncelleme (updatedAt) kazanır. Silinen görev "silindi" işaretiyle (tombstone) tutulur, böylece diğer cihazdan geri gelmez.
5. Senkrondan sonra Android widget'ı ve Windows penceresi listeyi yeniler.

**Veri modeli (görev):** id, başlık, notlar, renk, etiketler, deadline (tekrarlayan görevde boş), tekrarlayan mı, tekrar (günlük/haftalık), yapıldı mı, yapıldı zamanı (doneAt), silindi mi, updatedAt.

**Deadline:** Serbest metin kutusu; tarih/saat seçici yok. `GG.AA.YYYY SS:DD` biçiminde yazılırsa (saat yoksa 23:59) sıralamada ve geçmiş vurgusunda kullanılır; okunamazsa metin olduğu gibi gösterilir, sıralamada sona düşer.

**Widget:** Glance widget ve uygulama aynı yerel veritabanını okur. Widget'tan yapılan işaretleme, silme ve ekleme yerel veritabanına yazılır ve widget anında yenilenir; widget'ın kendi başına buluta bağlanması gerekmez.

**Google izin ekranı (bir kerelik kurulum):** Kendi Google Cloud projesinde Drive API açılır ve bir OAuth istemcisi oluşturulur. Proje "Testing" durumunda kalırsa giriş 7 günde bir yenilenmek zorunda kalır; kalıcı giriş için uygulamayı "Production"a almak gerekir. drive.appdata hassas olmayan bir kapsam olduğundan yalnızca temel doğrulama gerekir; kişisel kullanımda "doğrulanmamış uygulama" uyarısı bir kez geçilir. Windows'ta giriş tarayıcıda yapılır (yerel geri çağırma + PKCE). Bu bilgiler kurulum sırasında Google'ın güncel dokümanından doğrulanmalı.

## Açık sorular

- [x] Tekrarlayan görev nasıl davransın? Karar: (b) günlük/haftalık seçilir, "yapıldı" işareti dönem sonunda (gün, hafta pazartesi başlar) kendiliğinden sıfırlanır. Sıfırlama okuma anında hesaplanır.
- [x] Kendi Google Cloud projesini açıp OAuth istemcisi oluşturma: kabul edildi. İki istemci: "Masaüstü uygulaması" (Windows) ve "Android" (paket `com.sticky.reminder` + kalıcı imza anahtarının SHA-1 değeri). Proje "Production"a alınacak.
- [ ] Windows'ta oturum açılışında otomatik başlasın mı?
- [x] Widget'ta çok görev olursa: kaydırılabilir liste (uygulandı ve telefonda denendi).
- [x] Android sürümü ve telefon: Samsung Galaxy A24 (SM-A245F), Android 16. minSdk 26.
- [x] Uygulama adı: "Sticky" (Android'de ikon adı; paket `com.sticky.reminder`, Windows ürün adı `sticky-reminder`).

## Veri kullanımı (ölçüldü, Ekim 2026; dosyada ~25 kayıt, ~5,5 KB)

Her senkron dosyanın tamamını indirir; maliyetin çoğu TLS el sıkışmasıdır, görev sayısı değil.

| Senkron türü | Windows (TLS yükü, 2 bağlantı) | Android (IP düzeyi, TrafficStats) |
| --- | --- | --- |
| Değişiklik yok | ~13 KB (↑2,2 ↓11,1) | ~4,5 KB sıcak, ~9,5 KB soğuk (uygulama açılışı) |
| Yükleme (ekle/sil/yapıldı) | ~20 KB (↑8,1 ↓12,4) | ~12,5 KB (↑7,8 ↓4,7) |

- Windows'ta her senkron belirteç yeniler (oauth2 bağlantısı ≈ 5,5 KB), Android'de belirteci Play Services önbellekler (~3 KB, saatte en çok bir kez; ayrı uygulama olduğu için ölçüme girmez).
- Kayıt başına: yüklemede +~240 B (sıkıştırılmaz), indirmede +30-65 B (gzip). 100 görev ≈ 23,5 KB ham / 3,2 KB gzip; 1000 görev ≈ 234 KB ham / 28 KB gzip. Silinenler (tombstone) hiç temizlenmediği için dosyada kalır (100 kayıt ≈ 22 KB ham).
- Günlük tahmin (günde 15 değişiklik, telefon 20 kez açılıyor): Android ≈ 0,8-1 MB/gün (≈ 25-30 MB/ay), Windows pencere 10 saat açıksa ≈ 2 MB/gün (≈ 60 MB/ay), 24 saat açıksa ≈ 4 MB/gün (≈ 125 MB/ay). Toplam ≈ 90-150 MB/ay; çoğu boşta yapılan periyodik kontrollerin el sıkışmasıdır.
- Gerekirse azaltma yolları (uygulanmadı): Windows'ta erişim belirtecini ~50 dk önbelleğe almak (senkron başına −5,5 KB), Windows periyodunu 5 dk'dan 15 dk'ya çıkarmak (÷3), değişiklik yokken yalnızca sürüm sorgulamak, 90 günden eski tombstone'ları temizlemek.

## Windows uygulama notları (Aşama 6)

- Kurulum: `src-tauri/target/release/bundle/nsis/sticky-reminder_0.1.0_x64-setup.exe` (~2,4 MB, yalnızca bu kullanıcı için, Türkçe). Google ayarı `%APPDATA%\com.sticky.reminder\oauth.json` içinde olmalıdır (git'e girmez).
- Otomatik başlama: `HKCU\...\Run\Sticky`. Kurulu sürümün ilk çalıştırmasında açılır, sonra başlık çubuğundaki ⏻ düğmesiyle açılıp kapatılır; geliştirme sürümü kayıt defterine yazmaz. Kaldırırken `installer-hooks.nsh` girdiyi siler; veri ve Google girişi korunur.
- Çıkış (Windows ve Android) yalnızca bu cihazda geçerlidir. Google'daki izin tüm cihazlar için ortaktır (aynı Cloud projesi), belirteci iptal etmek diğer cihazı da çıkarır (denendi); bu yüzden çıkışta iptal yapılmaz. Tamamen kaldırmak için Google Hesabım > Güvenlik > Üçüncü taraf erişimi.

## Widget uygulama notları (Aşama 5)

- Jetpack Glance (`glance-appwidget` 1.2.0). Varsayılan 4×4 hücre (Samsung'ta 4×3 yerleşti), yeniden boyutlandırılabilir, `updatePeriodMillis` 30 dk.
- İçerik: tekrarlayan görevler üstte, açık görevler altta; yapılan (tekrarlayan olmayan) görevler görünmez. Onay kutusu = yapıldı, `×` = sil, `+` = hızlı ekleme penceresi (`QuickAddActivity`), başlık/satır dokunuşu = uygulamayı açar.
- Widget'ta yazı kutusu olamaz (Android kısıtı); bu yüzden `+` küçük bir pencere açar.
- Glance açık oturumda `provideGlance`'ı yeniden çağırmaz; widget veriyi `StateFlow`'dan okur ve her değişiklikte `updateStickyWidgets()` önce akışı tazeler.
- Release (R8) için `proguard-rules.pro`: `ActionCallback` sınıflarının boş yapıcıları korunmalı; yoksa onay kutusu ve silme sessizce çalışmaz.
- Uygulamada "Ana ekrana ekle" düğmesi (widget henüz yoksa) Android'in kendi pencerelerini açar.

## Senkron uygulama notları (Aşama 4)

- Drive dosyası `sticky.json`: `{ "version": 1, "tasks": [...] }`, silinenler `deleted` işaretiyle dahil. Daha yeni sürümlü dosya okunursa senkron durur.
- Birleştirme: en yeni `updatedAt` kazanır; eşitlikte silinmiş olan, o da değilse uzaktaki sürüm kazanır. Kurallar `shared/sync-cases.json` dosyasında senaryo olarak durur; hem Rust hem Kotlin testleri bu dosyayı okur.
- Senkron sırasında kullanıcı bir görevi değiştirirse o satır ezilmez ve bekleyen kalır (updatedAt karşılaştırmasıyla korunur).
- Android imzası: `~/.android/sticky.keystore` (yedeğini al; kaybolursa güncelleme kurulamaz ve Google'daki Android istemcisi yeniden kaydedilmelidir). Bilgileri git'e girmeyen `android/keystore.properties` içindedir.

## Aksiyon planı

Her aşama sonunda çalışan bir şey çıkar; sonraki aşamaya geçmeden denenir.

1. **Hazırlık:** Claude Code kurulumu, proje klasörü, bu dosya ve CLAUDE.md. Açık soruları kapat; Google Cloud projesi, Drive API ve OAuth istemcisi (Production'a al); Rust ve Android Studio kurulumu.
2. **Windows çekirdek (Tauri):** Yerel veritabanı, görev ekleme/silme/yapıldı, deadline, renk ve etiket, tekrarlayan görevler bölümü, küçük sticky penceresi, konum/boyut hatırlama. Henüz senkron yok.
3. **Android çekirdek (Kotlin):** Aynı görev modeli ve ekranlar.
4. **Drive senkron:** Google girişi (Windows'ta tarayıcıdan, Android'de hesap seçiciden), elle Kaydet + otomatik senkron, birleştirme. Test: uçak modunda ekle, internete bağlan, diğer cihazda gör.
5. **Android widget (Glance):** Büyük, sabit; işaretleme, silme, ekleme; senkron sonrası yenileme.
6. **Cilalama:** Otomatik başlama, kurulum dosyaları (Windows installer, Android APK), Android çıkış düğmesi, git deposu. (JSON dışa aktarma kapsamdan çıkarıldı.)

Claude Code'a verilecek ilk komut önerisi: "REQUIREMENTS.md'yi oku, 2. aşamayı planla ve onayımı bekle."

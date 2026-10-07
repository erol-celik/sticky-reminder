# Sticky

[English](README.md) · **Türkçe**

**Windows** ve **Android** için küçük, hesapsız-hissi veren bir görev uygulaması. Görevlerin her cihazda yerel bir veritabanında durur ve **kendi Google Drive'ındaki gizli bir uygulama klasörü** üzerinden senkronlanır. Bildirim yok, analitik yok, Google Drive dışında sunucu yok.

- **Windows:** sürekli açık duran küçük, yapışkan not görünümlü pencere (Tauri 2 + Rust). 2,4 MB kurulum dosyası.
- **Android:** Jetpack Compose uygulaması ve uygulama gibi çalışan bir **ana ekran widget'ı** (yapıldı, sil, ekle). 2,3 MB APK.

> Arayüz Türkçedir. Kod ve bu README herkesin okuması için açıktır; bkz. [Lisans](#lisans).

## Özellikler

- **Hızlı ekleme:** tek satır, Enter.
- **Görevler:** yapıldı / sil, isteğe bağlı not, renk, serbest etiketler ve etikete göre filtre.
- **Deadline:** düz bir metin kutusu. `GG.AA.YYYY SS:DD` yaz (saat isteğe bağlı, yoksa 23:59 sayılır); görev buna göre sıralanır, geçmişse vurgulanır. Başka bir metin olduğu gibi gösterilir ve sıralamada sona düşer. Tarih seçici ve hatırlatma yok.
- **Tekrarlayan görevler:** günlük ya da haftalık, listenin en üstünde ayrı bölümde durur. "Yapıldı" işareti gün (ya da pazartesi başlayan hafta) bitince kendiliğinden sıfırlanır.
- **Çevrimdışı öncelikli:** her şey önce yerel SQLite veritabanına yazılır. İnternet yokken ekleme, düzenleme, silme çalışır; bekleyen değişiklik sayısı görünür.
- **Senkron (Google Drive, yalnızca `drive.appdata` kapsamı):** uygulamanın gizli klasöründe tek bir JSON dosyası. Uygulama Drive'ındaki başka hiçbir dosyayı göremez.
  - Elle **Kaydet** düğmesi (gönderir ve getirir); ayrıca uygulama açılırken, değişiklikten yaklaşık 5 saniye sonra, internet geri gelince ve periyodik olarak otomatik senkron.
  - Birleştirme görev bazındadır: en son `updatedAt` kazanır. Silinen görevler "silindi" işaretiyle (tombstone) tutulur, böylece başka cihazdan geri gelmez.
- **Windows:** konum ve boyutu hatırlar, isteğe bağlı Windows ile başlama (başlık çubuğundaki düğme), tek sanal masaüstünde durur.
- **Android widget:** büyük, yeniden boyutlandırılabilir, kaydırılabilir. Ana ekrandan yapıldı, sil ve ekle (`+` küçük bir pencere açar).

## Senkron nasıl çalışır

```
cihaz A (SQLite) ──┐                    ┌── cihaz B (SQLite)
                   ├── sticky.json ─────┤
       Google Drive (gizli appDataFolder)
```

Her senkron dosyayı indirir, yerel değişikliklerle birleştirir ve bir şey değiştiyse sonucu geri yükler. Bu arada Drive'daki dosya değiştiyse birleştirme tekrarlanır, yani başka bir cihazın yüklemesi asla ezilmez. Birleştirme kuralları senaryo olarak [`shared/sync-cases.json`](shared/sync-cases.json) dosyasında yazılıdır ve **hem Rust hem Kotlin testleri aynı dosyayı çalıştırır**; iki platform birbirinden ayrışamaz.

## Depo yapısı

| Yol | Ne |
| --- | --- |
| `src/` | Windows arayüzü (TypeScript + Vite, çatı yok) |
| `src-tauri/` | Windows uygulaması (Rust, Tauri 2): SQLite, Google girişi (PKCE), Drive istemcisi, senkron, otomatik başlama, NSIS kurulum dosyası |
| `android/` | Android uygulaması (Kotlin, Compose, Glance widget, WorkManager) |
| `shared/` | İki test paketinin ortak kullandığı birleştirme senaryoları |
| `REQUIREMENTS.md` | Gereksinimler, kararlar ve ölçümler |

## Kendin derle

**Kendi Google Cloud projen** gerekir: bu depoda hiçbir istemci kimliği ya da sır yoktur.

### 1. Google Cloud (bir kerelik, ücretsiz)

1. Bir proje oluştur ve **Google Drive API**'yi etkinleştir.
2. OAuth izin ekranı: kullanıcı türü **Harici**, yalnızca `.../auth/drive.appdata` kapsamını ekle (hassas olmayan), sonra **üretime yayınla** ("Test" durumunda yenileme belirteci 7 günde ölür). Üretim için bir ana sayfa ve gizlilik politikası adresi gerekir; basit herkese açık bir sayfa yeterli.
3. İki OAuth istemcisi oluştur:
   - **Masaüstü uygulaması** (Windows için),
   - **Android**: paket adı `com.sticky.reminder` ve APK'yı imzaladığın anahtarın SHA-1 parmak izi.

### 2. Windows

Gerekenler: Rust (kararlı sürüm), Node.js 20.19+ ya da 22, Visual Studio Build Tools (C++ iş yükü), WebView2 (Windows 11'de hazır).

```powershell
npm install
npm run tauri dev        # geliştirme
npm run tauri build      # kurulum dosyası: src-tauri/target/release/bundle/nsis/
cd src-tauri; cargo test # 62 test
```

Masaüstü istemcini `%APPDATA%\com.sticky.reminder\oauth.json` dosyasına koy:

```json
{ "clientId": "xxxx.apps.googleusercontent.com", "clientSecret": "GOCSPX-xxxx" }
```

Sonra penceredeki alt çubuktan **Google ile giriş**'e bas. (Masaüstü uygulamalarda Google istemci gizli anahtarını gerçek bir sır saymaz.)

### 3. Android

Gerekenler: JDK 17+, Android SDK (platform 37, build-tools 37; `sdk.dir` değerini `android/local.properties` içine ya da `ANDROID_HOME` olarak ver).

```powershell
cd android
.\gradlew.bat testDebugUnitTest     # 43 test
.\gradlew.bat assembleRelease       # app/build/outputs/apk/release/
adb install -r app\build\outputs\apk\release\app-release.apk
```

İmza: `android/keystore.properties` dosyasını oluştur (git'e girmez): `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Yoksa derleme debug anahtarına döner. **Hangi anahtarı kullanırsan kullan, SHA-1 değeri Android OAuth istemcisine kayıtlı olanla aynı olmalı**, yoksa giriş çalışmaz. O anahtarın yedeğini al.

Widget'ı eklemek için ana ekrana uzun bas → Widget'lar → Sticky, ya da uygulamadaki "Ana ekrana ekle"yi kullan.

## Veri kullanımı (ölçüldü)

Maliyetin çoğu görev verisi değil, TLS el sıkışmasıdır:

| Senkron | Windows | Android |
| --- | --- | --- |
| değişiklik yok | ~13 KB | ~4,5 KB (uygulama açılışında ~9,5 KB) |
| yükleme (ekle / sil / yapıldı) | ~20 KB | ~12,5 KB |

Tipik kullanımda aylık **yaklaşık 85-165 MB**, Windows penceresinin ne kadar açık kaldığına göre. Ayrıntılar ve azaltma yolları [`REQUIREMENTS.md`](REQUIREMENTS.md) içinde.

## Gizlilik

- Yalnızca Google ile iletişim kurar (giriş ve Drive). Reklam, analitik ya da başka sunucu yok.
- Windows yenileme belirtecini **Windows Kimlik Bilgisi Yöneticisi**'nde saklar; Android yenileme belirteci saklamaz (Play Services kısa ömürlü erişim belirteçleri verir).
- Çıkış cihaz başınadır ve görevleri silmez. Uygulamanın erişimini tamamen kaldırmak için: Google Hesabı → Güvenlik → Üçüncü taraf erişimi.

## Bilinen sınırlar

- Windows kurulum dosyası **kod imzasız**; başka bilgisayarlarda SmartScreen uyarabilir.
- Silinen görev kayıtları (tombstone) henüz hiç temizlenmiyor (çok küçük; normal kullanımda dosya küçük kalır).
- Samsung Galaxy A24 (Android 16) ve Windows 11 dışında denenmedi.

## Lisans

Henüz bir lisans seçilmedi, yani tüm haklar saklıdır. Projeyi kullanmak ya da katkı vermek istersen lütfen bir issue aç.

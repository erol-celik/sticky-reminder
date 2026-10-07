//! Google girişi: masaüstü uygulaması için 127.0.0.1 geri çağırma + PKCE.
//! Yenileme belirteci Windows Kimlik Bilgisi Yöneticisi'nde saklanır, dosyada tutulmaz.

use std::io::{ErrorKind, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::Path;
use std::time::{Duration, Instant};

use serde::Deserialize;
use sha2::{Digest, Sha256};
use uuid::Uuid;

use crate::http;
use crate::store::Res;

/// Yalnızca uygulamanın kendi gizli Drive klasörü.
pub const SCOPE: &str = "https://www.googleapis.com/auth/drive.appdata";
/// Oturum geçersizse (belirteç yok, iptal edilmiş ya da süresi dolmuş) dönen hata ön eki.
pub const SIGNED_OUT: &str = "SIGNED_OUT: yeniden giriş gerekli";

const AUTH_URL: &str = "https://accounts.google.com/o/oauth2/v2/auth";
const TOKEN_URL: &str = "https://oauth2.googleapis.com/token";
const KEYRING_SERVICE: &str = "com.sticky.reminder";
const KEYRING_USER: &str = "google-refresh-token";

fn s<E: ToString>(e: E) -> String {
    e.to_string()
}

pub fn is_signed_out(error: &str) -> bool {
    error.starts_with("SIGNED_OUT")
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OAuthConfig {
    pub client_id: String,
    pub client_secret: String,
}

/// `oauth.json` dosyasını okur. Değerlerin başındaki/sonundaki boşluklar (kopyalarken
/// gelenler) ayıklanır; hata iletilerine değer yazılmaz.
pub fn load_config(path: &Path) -> Res<OAuthConfig> {
    let text = std::fs::read_to_string(path)
        .map_err(|_| format!("Google ayar dosyası bulunamadı: {}", path.display()))?;
    // Kopyalarken tırnakların içine karışan satır sonu/sekme gibi denetim karakterleri
    // geçerli JSON değildir; ayıklanır.
    let cleaned: String = text.trim_start_matches('\u{feff}').chars().filter(|c| !c.is_control()).collect();
    let mut config: OAuthConfig =
        serde_json::from_str(&cleaned).map_err(|e| format!("oauth.json okunamadı: {e}"))?;
    config.client_id = config.client_id.trim().to_string();
    config.client_secret = config.client_secret.trim().to_string();
    let missing = config.client_id.is_empty()
        || config.client_secret.is_empty()
        || config.client_id.contains("BURAYA")
        || config.client_secret.contains("BURAYA");
    if missing {
        return Err("oauth.json içindeki clientId / clientSecret boş ya da yer tutucu".into());
    }
    Ok(config)
}

pub fn agent() -> ureq::Agent {
    ureq::Agent::config_builder()
        .http_status_as_error(false)
        .timeout_global(Some(Duration::from_secs(30)))
        .build()
        .into()
}

// --- PKCE ---------------------------------------------------------------------------------

/// 64 karakterlik rastgele doğrulayıcı (RFC 7636: 43-128 karakter, yalnızca güvenli karakterler).
pub fn new_verifier() -> String {
    format!("{}{}", Uuid::new_v4().simple(), Uuid::new_v4().simple())
}

pub fn challenge_for(verifier: &str) -> String {
    base64url(&Sha256::digest(verifier.as_bytes()))
}

fn base64url(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let n = (u32::from(chunk[0]) << 16)
            | (u32::from(*chunk.get(1).unwrap_or(&0)) << 8)
            | u32::from(*chunk.get(2).unwrap_or(&0));
        out.push(ALPHABET[(n >> 18) as usize & 63] as char);
        out.push(ALPHABET[(n >> 12) as usize & 63] as char);
        if chunk.len() > 1 {
            out.push(ALPHABET[(n >> 6) as usize & 63] as char);
        }
        if chunk.len() > 2 {
            out.push(ALPHABET[n as usize & 63] as char);
        }
    }
    out
}

pub fn urlencode(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for b in text.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

pub fn percent_decode(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' if i + 2 < bytes.len() => {
                let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).ok();
                match hex.and_then(|h| u8::from_str_radix(h, 16).ok()) {
                    Some(b) => {
                        out.push(b);
                        i += 3;
                    }
                    None => {
                        out.push(b'%');
                        i += 1;
                    }
                }
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

pub fn auth_url(config: &OAuthConfig, redirect_uri: &str, challenge: &str, state: &str) -> String {
    let params = [
        ("client_id", config.client_id.as_str()),
        ("redirect_uri", redirect_uri),
        ("response_type", "code"),
        ("scope", SCOPE),
        ("code_challenge", challenge),
        ("code_challenge_method", "S256"),
        ("state", state),
        // Yenileme belirteci için; "consent" her girişte belirtecin verilmesini sağlar.
        ("access_type", "offline"),
        ("prompt", "consent"),
    ];
    let query: Vec<String> = params
        .iter()
        .map(|(k, v)| format!("{k}={}", urlencode(v)))
        .collect();
    format!("{AUTH_URL}?{}", query.join("&"))
}

// --- Geri çağırma sunucusu -------------------------------------------------------------------

/// "GET /yol?a=1&b=2 HTTP/1.1" satırını (yol, sorgu çiftleri) olarak ayrıştırır.
pub fn parse_request_target(request: &str) -> Option<(String, Vec<(String, String)>)> {
    let line = request.lines().next()?;
    let mut parts = line.split_whitespace();
    if parts.next()? != "GET" {
        return None;
    }
    let target = parts.next()?;
    let (path, query) = target.split_once('?').unwrap_or((target, ""));
    let pairs = query
        .split('&')
        .filter(|p| !p.is_empty())
        .map(|p| {
            let (k, v) = p.split_once('=').unwrap_or((p, ""));
            (percent_decode(k), percent_decode(v))
        })
        .collect();
    Some((path.to_string(), pairs))
}

pub struct Loopback {
    listener: TcpListener,
    port: u16,
}

impl Loopback {
    /// Boş bir yerel bağlantı noktasında yalnızca 127.0.0.1'i dinler.
    pub fn bind() -> Res<Self> {
        let listener = TcpListener::bind("127.0.0.1:0").map_err(s)?;
        let port = listener.local_addr().map_err(s)?.port();
        Ok(Self { listener, port })
    }

    pub fn redirect_uri(&self) -> String {
        format!("http://127.0.0.1:{}", self.port)
    }

    /// Tarayıcının Google'dan döndüğü isteği bekler; yetkilendirme kodunu verir.
    pub fn wait_for_code(&self, expected_state: &str, timeout: Duration) -> Res<String> {
        self.listener.set_nonblocking(true).map_err(s)?;
        let deadline = Instant::now() + timeout;
        loop {
            if Instant::now() > deadline {
                return Err("Giriş zaman aşımına uğradı".into());
            }
            match self.listener.accept() {
                Ok((mut stream, _)) => {
                    if let Some(result) = handle_connection(&mut stream, expected_state) {
                        return result;
                    }
                }
                Err(e) if e.kind() == ErrorKind::WouldBlock => {
                    std::thread::sleep(Duration::from_millis(100));
                }
                Err(e) => return Err(s(e)),
            }
        }
    }
}

/// Giriş yanıtı bu bağlantıdaysa sonucu verir; değilse (örn. favicon isteği) `None`.
fn handle_connection(stream: &mut TcpStream, expected_state: &str) -> Option<Res<String>> {
    stream.set_nonblocking(false).ok();
    stream.set_read_timeout(Some(Duration::from_secs(5))).ok();
    let mut buffer = [0u8; 8192];
    let read = stream.read(&mut buffer).unwrap_or(0);
    let request = String::from_utf8_lossy(&buffer[..read]).into_owned();

    let (path, query) = parse_request_target(&request)?;
    let get = |name: &str| query.iter().find(|(k, _)| k == name).map(|(_, v)| v.clone());
    if path != "/" || (get("code").is_none() && get("error").is_none()) {
        respond(stream, "404 Not Found", "");
        return None;
    }

    let result = if get("state").as_deref() != Some(expected_state) {
        Err("Giriş doğrulanamadı (state uyuşmadı)".to_string())
    } else if let Some(error) = get("error") {
        Err(format!("Giriş reddedildi: {error}"))
    } else {
        get("code").ok_or_else(|| "Google kod göndermedi".to_string())
    };
    let page = if result.is_ok() {
        "<h2>Giriş tamamlandı</h2><p>Bu sekmeyi kapatıp Sticky'ye dönebilirsin.</p>"
    } else {
        "<h2>Giriş tamamlanamadı</h2><p>Sticky'ye dönüp tekrar deneyebilirsin.</p>"
    };
    respond(stream, "200 OK", page);
    Some(result)
}

fn respond(stream: &mut TcpStream, status: &str, body: &str) {
    let html = format!(
        "<!doctype html><meta charset=\"utf-8\"><title>Sticky</title>\
         <body style=\"font-family:system-ui,sans-serif;background:#e9f1fb;color:#1f2f4d;padding:2em\">{body}</body>"
    );
    let html = if body.is_empty() { String::new() } else { html };
    let response = format!(
        "HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{html}",
        html.len()
    );
    let _ = stream.write_all(response.as_bytes());
    let _ = stream.flush();
}

pub fn open_browser(url: &str) -> Res<()> {
    std::process::Command::new("rundll32")
        .arg("url.dll,FileProtocolHandler")
        .arg(url)
        .spawn()
        .map(|_| ())
        .map_err(|e| format!("Tarayıcı açılamadı: {e}"))
}

// --- Belirteç alışverişi -----------------------------------------------------------------------

#[derive(Deserialize)]
struct TokenResponse {
    access_token: String,
    refresh_token: Option<String>,
    #[serde(default)]
    scope: String,
}

/// Girişten dönen sonuç: yalnızca yenileme belirteci saklanır; erişim belirteci her senkronda
/// yenileme belirteciyle yeniden alınır.
pub struct Granted {
    pub refresh_token: Option<String>,
}

fn token_call(agent: &ureq::Agent, form: &[(&str, &str)]) -> Res<TokenResponse> {
    let (status, body) = http::finish(agent.post(TOKEN_URL).send_form(form.iter().copied()))?;
    if status == 200 {
        return serde_json::from_str(&body).map_err(|e| format!("Google yanıtı okunamadı: {e}"));
    }
    if body.contains("invalid_grant") {
        return Err(SIGNED_OUT.to_string());
    }
    Err(format!("Google {status}: {}", http::shorten(&body)))
}

pub fn exchange_code(
    agent: &ureq::Agent,
    config: &OAuthConfig,
    code: &str,
    verifier: &str,
    redirect_uri: &str,
) -> Res<Granted> {
    let response = token_call(
        agent,
        &[
            ("grant_type", "authorization_code"),
            ("code", code),
            ("code_verifier", verifier),
            ("redirect_uri", redirect_uri),
            ("client_id", &config.client_id),
            ("client_secret", &config.client_secret),
        ],
    )?;
    if !response.scope.split_whitespace().any(|granted| granted == SCOPE) {
        return Err("Drive izni verilmedi; girişte izin kutusunu işaretlemelisin".into());
    }
    Ok(Granted { refresh_token: response.refresh_token })
}

/// Yenileme belirteciyle yeni bir erişim belirteci alır.
pub fn refresh_access_token(
    agent: &ureq::Agent,
    config: &OAuthConfig,
    refresh_token: &str,
) -> Res<String> {
    let response = token_call(
        agent,
        &[
            ("grant_type", "refresh_token"),
            ("refresh_token", refresh_token),
            ("client_id", &config.client_id),
            ("client_secret", &config.client_secret),
        ],
    )?;
    Ok(response.access_token)
}

// --- Yenileme belirtecinin saklanması ------------------------------------------------------------

fn entry() -> Res<keyring::Entry> {
    keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER).map_err(s)
}

pub fn save_refresh_token(token: &str) -> Res<()> {
    entry()?.set_password(token).map_err(s)
}

pub fn load_refresh_token() -> Res<Option<String>> {
    match entry()?.get_password() {
        Ok(token) => Ok(Some(token)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(e) => Err(s(e)),
    }
}

pub fn delete_refresh_token() -> Res<()> {
    match entry()?.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
        Err(e) => Err(s(e)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pkce_rfc7636_ornegi() {
        // RFC 7636 Ek B'deki örnek.
        assert_eq!(
            challenge_for("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        );
    }

    #[test]
    fn dogrulayici_uzunlugu_ve_karakterleri() {
        let v = new_verifier();
        assert_eq!(v.len(), 64);
        assert!(v.chars().all(|c| c.is_ascii_hexdigit()));
        assert_ne!(v, new_verifier());
    }

    #[test]
    fn base64url_dolgusuz() {
        assert_eq!(base64url(b""), "");
        assert_eq!(base64url(b"f"), "Zg");
        assert_eq!(base64url(b"fo"), "Zm8");
        assert_eq!(base64url(b"foo"), "Zm9v");
        assert_eq!(base64url(&[0xfb, 0xff]), "-_8");
    }

    #[test]
    fn urlencode_ve_decode_gidis_donus() {
        let text = "a b&c=d/é~._-";
        assert_eq!(percent_decode(&urlencode(text)), text);
        assert_eq!(urlencode("a b"), "a%20b");
        assert_eq!(percent_decode("a+b%20c%zz%"), "a b c%zz%");
    }

    #[test]
    fn yetkilendirme_adresi_gerekli_parametreleri_icerir() {
        let config = OAuthConfig { client_id: "id-1.apps".into(), client_secret: "x".into() };
        let url = auth_url(&config, "http://127.0.0.1:5000", "CH", "ST");
        assert!(url.starts_with("https://accounts.google.com/o/oauth2/v2/auth?"));
        for part in [
            "client_id=id-1.apps",
            "redirect_uri=http%3A%2F%2F127.0.0.1%3A5000",
            "response_type=code",
            "scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.appdata",
            "code_challenge=CH",
            "code_challenge_method=S256",
            "state=ST",
            "access_type=offline",
        ] {
            assert!(url.contains(part), "eksik: {part}\n{url}");
        }
        // Gizli anahtar tarayıcı adresine girmemeli.
        assert!(!url.contains("client_secret"));
    }

    #[test]
    fn istek_satiri_ayrisir() {
        let (path, q) = parse_request_target("GET /?code=4%2F0Ab&state=xyz HTTP/1.1\r\nHost: x\r\n\r\n").unwrap();
        assert_eq!(path, "/");
        assert_eq!(q, vec![("code".to_string(), "4/0Ab".to_string()), ("state".to_string(), "xyz".to_string())]);
        assert!(parse_request_target("POST / HTTP/1.1").is_none());
        assert!(parse_request_target("").is_none());
        let (p, q) = parse_request_target("GET /favicon.ico HTTP/1.1").unwrap();
        assert_eq!((p.as_str(), q.len()), ("/favicon.ico", 0));
    }

    fn browser_call(port: u16, target: &str) -> String {
        let mut stream = TcpStream::connect(("127.0.0.1", port)).unwrap();
        write!(stream, "GET {target} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").unwrap();
        let mut response = String::new();
        stream.read_to_string(&mut response).unwrap();
        response
    }

    #[test]
    fn geri_cagirma_kodu_dogru_state_ile_alir() {
        let lb = Loopback::bind().unwrap();
        let port = lb.port;
        let t = std::thread::spawn(move || {
            // önce favicon benzeri alakasız bir istek, sonra gerçek yanıt
            let first = browser_call(port, "/favicon.ico");
            let second = browser_call(port, "/?code=KOD123&state=S1");
            (first, second)
        });
        let code = lb.wait_for_code("S1", Duration::from_secs(10)).unwrap();
        assert_eq!(code, "KOD123");
        let (first, second) = t.join().unwrap();
        assert!(first.starts_with("HTTP/1.1 404"));
        assert!(second.starts_with("HTTP/1.1 200") && second.contains("Giriş tamamlandı"));
    }

    #[test]
    fn geri_cagirma_yanlis_state_reddeder() {
        let lb = Loopback::bind().unwrap();
        let port = lb.port;
        let t = std::thread::spawn(move || browser_call(port, "/?code=KOD&state=BASKASI"));
        let err = lb.wait_for_code("S1", Duration::from_secs(10)).unwrap_err();
        assert!(err.contains("state"), "{err}");
        assert!(t.join().unwrap().contains("Giriş tamamlanamadı"));
    }

    #[test]
    fn geri_cagirma_kullanici_reddini_bildirir() {
        let lb = Loopback::bind().unwrap();
        let port = lb.port;
        let t = std::thread::spawn(move || browser_call(port, "/?error=access_denied&state=S1"));
        let err = lb.wait_for_code("S1", Duration::from_secs(10)).unwrap_err();
        assert!(err.contains("access_denied"), "{err}");
        t.join().unwrap();
    }

    #[test]
    fn geri_cagirma_zaman_asimi() {
        let lb = Loopback::bind().unwrap();
        let err = lb.wait_for_code("S1", Duration::from_millis(300)).unwrap_err();
        assert!(err.contains("zaman aşımı"), "{err}");
    }

    #[test]
    fn ayar_dosyasi_bosluklari_ayiklar_ve_dogrular() {
        let dir = std::env::temp_dir().join(format!("sticky-oauth-{}", Uuid::new_v4().simple()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("oauth.json");

        std::fs::write(&path, "{\"clientId\": \" abc.apps.googleusercontent.com\\n\", \"clientSecret\": \"GOCSPX-x \"}").unwrap();
        let c = load_config(&path).unwrap();
        assert_eq!(c.client_id, "abc.apps.googleusercontent.com");
        assert_eq!(c.client_secret, "GOCSPX-x");

        // Gerçek satır sonu tırnağın içine karışmış (kopyalama hatası): yine okunabilmeli.
        std::fs::write(&path, "{\"clientId\": \"abc.apps.googleusercontent.com\n\",\n \"clientSecret\": \"GOCSPX-x\r\n\"}").unwrap();
        let c = load_config(&path).unwrap();
        assert_eq!(c.client_id, "abc.apps.googleusercontent.com");
        assert_eq!(c.client_secret, "GOCSPX-x");

        std::fs::write(&path, "{\"clientId\": \"BURAYA_ISTEMCI_KIMLIGI\", \"clientSecret\": \"x\"}").unwrap();
        assert!(load_config(&path).unwrap_err().contains("boş"));

        std::fs::write(&path, "bozuk").unwrap();
        assert!(load_config(&path).unwrap_err().contains("okunamadı"));

        assert!(load_config(&dir.join("yok.json")).unwrap_err().contains("bulunamadı"));
        let _ = std::fs::remove_dir_all(&dir);
    }
}

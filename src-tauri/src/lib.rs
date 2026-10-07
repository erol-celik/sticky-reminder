mod autostart;
mod deadline;
mod drive;
mod http;
mod oauth;
mod store;
mod sync;
mod syncer;

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use rusqlite::Connection;
use serde::Serialize;
use store::{NewTask, Res, Task, TaskPatch};
use tauri::{Manager, State};
use uuid::Uuid;

/// Arayüzün kullandığı bağlantı. Senkron kendi bağlantısını açar; böylece ağ beklerken
/// liste okuma/yazma engellenmez.
struct Db(Mutex<Connection>);

struct Paths {
    db: PathBuf,
    oauth: PathBuf,
}

struct SyncRunning(Arc<AtomicBool>);

struct ResetOnDrop(Arc<AtomicBool>);

impl Drop for ResetOnDrop {
    fn drop(&mut self) {
        self.0.store(false, Ordering::SeqCst);
    }
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn with_conn<T>(db: &State<Db>, f: impl FnOnce(&Connection) -> Res<T>) -> Res<T> {
    let conn = db.0.lock().map_err(|e| e.to_string())?;
    f(&conn)
}

/// `tz_offset_min`: arayüzdeki `new Date().getTimezoneOffset()` değeri.
#[tauri::command]
fn list_tasks(db: State<Db>, tz_offset_min: i64) -> Res<Vec<Task>> {
    with_conn(&db, |c| store::list(c, now_ms(), tz_offset_min))
}

#[tauri::command]
fn add_task(db: State<Db>, task: NewTask) -> Res<String> {
    with_conn(&db, |c| store::add(c, task, now_ms()))
}

#[tauri::command]
fn update_task(db: State<Db>, id: String, patch: TaskPatch) -> Res<()> {
    with_conn(&db, |c| store::update(c, &id, patch, now_ms()))
}

#[tauri::command]
fn delete_task(db: State<Db>, id: String) -> Res<()> {
    with_conn(&db, |c| store::delete(c, &id, now_ms()))
}

#[tauri::command]
fn pending_count(db: State<Db>) -> Res<i64> {
    with_conn(&db, store::pending_count)
}

#[derive(Serialize)]
struct AutostartStatus {
    /// Geliştirme sürümü kayıt defterine yazılmaz; yalnızca kurulu (release) sürümde etkin.
    supported: bool,
    enabled: bool,
}

#[tauri::command]
fn autostart_status() -> AutostartStatus {
    AutostartStatus { supported: !cfg!(debug_assertions), enabled: autostart::is_enabled() }
}

#[tauri::command]
fn set_autostart(enabled: bool) -> Res<()> {
    if cfg!(debug_assertions) {
        return Err("Otomatik başlama yalnızca kurulu sürümde ayarlanır".into());
    }
    autostart::set_enabled(enabled)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AuthStatus {
    /// `oauth.json` okunabildi mi (Google istemci bilgileri).
    configured: bool,
    signed_in: bool,
    error: Option<String>,
}

#[tauri::command]
fn auth_status(paths: State<Paths>) -> AuthStatus {
    if let Err(error) = oauth::load_config(&paths.oauth) {
        return AuthStatus { configured: false, signed_in: false, error: Some(error) };
    }
    match oauth::load_refresh_token() {
        Ok(token) => AuthStatus { configured: true, signed_in: token.is_some(), error: None },
        Err(error) => AuthStatus { configured: true, signed_in: false, error: Some(error) },
    }
}

/// Tarayıcıda Google girişini başlatır ve bitene kadar bekler.
#[tauri::command]
async fn sign_in(paths: State<'_, Paths>) -> Res<()> {
    let oauth_path = paths.oauth.clone();
    tauri::async_runtime::spawn_blocking(move || -> Res<()> {
        let config = oauth::load_config(&oauth_path)?;
        let loopback = oauth::Loopback::bind()?;
        let redirect_uri = loopback.redirect_uri();
        let verifier = oauth::new_verifier();
        let state = Uuid::new_v4().simple().to_string();
        let url = oauth::auth_url(&config, &redirect_uri, &oauth::challenge_for(&verifier), &state);

        oauth::open_browser(&url)?;
        let code = loopback.wait_for_code(&state, Duration::from_secs(180))?;

        let granted =
            oauth::exchange_code(&oauth::agent(), &config, &code, &verifier, &redirect_uri)?;
        let refresh = granted.refresh_token.ok_or(
            "Google yenileme belirteci vermedi. Google Hesabım'da Sticky'nin erişimini kaldırıp \
             yeniden giriş yapmayı dene",
        )?;
        oauth::save_refresh_token(&refresh)
    })
    .await
    .map_err(|e| e.to_string())?
}

/// Oturumu bu bilgisayarda kapatır: belirteci Kimlik Bilgisi Yöneticisi'nden siler. Yerel görevlere
/// dokunulmaz. Google'daki izin **iptal edilmez**: izin tüm cihazlar için ortaktır (aynı Cloud
/// projesi), iptal etmek telefondaki oturumu da düşürürdü. Tamamen kaldırmak için Google Hesabım >
/// Güvenlik > Üçüncü taraf erişimi.
#[tauri::command]
async fn sign_out() -> Res<()> {
    tauri::async_runtime::spawn_blocking(oauth::delete_refresh_token)
        .await
        .map_err(|e| e.to_string())?
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SyncReport {
    at: i64,
    pulled: usize,
    uploaded: bool,
}

fn do_sync(db: &Path, oauth_path: &Path) -> Res<SyncReport> {
    let config = oauth::load_config(oauth_path)?;
    let refresh = oauth::load_refresh_token()?.ok_or_else(|| oauth::SIGNED_OUT.to_string())?;
    let agent = oauth::agent();
    let access = match oauth::refresh_access_token(&agent, &config, &refresh) {
        Err(error) if oauth::is_signed_out(&error) => {
            // İptal edilmiş ya da süresi dolmuş belirteç: kullanıcıdan yeniden giriş istenir.
            let _ = oauth::delete_refresh_token();
            return Err(error);
        }
        other => other?,
    };
    let conn = store::open(db)?;
    let outcome = syncer::run(&conn, &mut drive::DriveRemote::new(agent, access))?;
    Ok(SyncReport { at: now_ms(), pulled: outcome.pulled, uploaded: outcome.uploaded })
}

/// Drive ile tam senkron: indir, birleştir, gerekirse yükle. Hata iletisi "SIGNED_OUT" ile
/// başlıyorsa yeniden giriş gerekir; "BUSY" ile başlıyorsa başka bir senkron sürüyordur.
#[tauri::command]
async fn sync_now(paths: State<'_, Paths>, running: State<'_, SyncRunning>) -> Res<SyncReport> {
    let (db, oauth_path) = (paths.db.clone(), paths.oauth.clone());
    let flag = running.0.clone();
    tauri::async_runtime::spawn_blocking(move || {
        if flag.swap(true, Ordering::SeqCst) {
            return Err("BUSY: bir senkron zaten sürüyor".to_string());
        }
        let _reset = ResetOnDrop(flag);
        do_sync(&db, &oauth_path)
    })
    .await
    .map_err(|e| e.to_string())?
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .setup(|app| {
            let dir = app.path().app_data_dir()?;
            std::fs::create_dir_all(&dir)?;
            let db_path = dir.join("sticky.db");
            let conn = store::open(&db_path)?;
            app.manage(Db(Mutex::new(conn)));
            app.manage(Paths { db: db_path, oauth: dir.join("oauth.json") });
            app.manage(SyncRunning(Arc::new(AtomicBool::new(false))));

            // İlk çalıştırmada (kurulu sürüm) açılışta başlama açılır; sonrasında kullanıcının seçimi korunur.
            let first_run_marker = dir.join("autostart-initialized");
            if !cfg!(debug_assertions) && !first_run_marker.exists() {
                let _ = autostart::set_enabled(true);
                let _ = std::fs::write(&first_run_marker, "1");
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            list_tasks,
            add_task,
            update_task,
            delete_task,
            pending_count,
            autostart_status,
            set_autostart,
            auth_status,
            sign_in,
            sign_out,
            sync_now
        ])
        .run(tauri::generate_context!())
        .expect("Tauri uygulaması başlatılamadı");
}

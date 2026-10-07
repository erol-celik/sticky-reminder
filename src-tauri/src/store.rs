use std::cmp::Ordering;
use std::path::Path;

use rusqlite::{params, Connection, OptionalExtension, Row};
use serde::{Deserialize, Serialize};

use crate::deadline::parse_deadline;
use crate::sync::SyncTask;

const DAY_MS: i64 = 86_400_000;
const COLS: &str =
    "id, title, notes, color, tags, deadline, recurring, recurrence, done, done_at, updated_at";

pub type Res<T> = Result<T, String>;

fn s<E: ToString>(e: E) -> String {
    e.to_string()
}

/// Arayüze giden görev. `done`, tekrarlayan görevlerde dönem sonu sıfırlaması
/// uygulanmış etkin değerdir; `deadline_key` yazılan metnin okunabilen hâlidir.
#[derive(Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Task {
    pub id: String,
    pub title: String,
    pub notes: String,
    pub color: String,
    pub tags: Vec<String>,
    pub deadline: String,
    pub deadline_key: Option<i64>,
    pub recurring: bool,
    pub recurrence: Option<String>,
    pub done: bool,
    pub done_at: Option<i64>,
    pub updated_at: i64,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewTask {
    pub title: String,
    #[serde(default)]
    pub recurring: bool,
    pub recurrence: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskPatch {
    pub title: Option<String>,
    pub notes: Option<String>,
    pub color: Option<String>,
    pub tags: Option<Vec<String>>,
    pub deadline: Option<String>,
    pub recurring: Option<bool>,
    pub recurrence: Option<String>,
    pub done: Option<bool>,
}

struct Raw {
    id: String,
    title: String,
    notes: String,
    color: String,
    tags: String,
    deadline: String,
    recurring: bool,
    recurrence: Option<String>,
    done: bool,
    done_at: Option<i64>,
    updated_at: i64,
}

fn raw_from_row(r: &Row) -> rusqlite::Result<Raw> {
    Ok(Raw {
        id: r.get(0)?,
        title: r.get(1)?,
        notes: r.get(2)?,
        color: r.get(3)?,
        tags: r.get(4)?,
        deadline: r.get(5)?,
        recurring: r.get(6)?,
        recurrence: r.get(7)?,
        done: r.get(8)?,
        done_at: r.get(9)?,
        updated_at: r.get(10)?,
    })
}

pub fn open(path: &Path) -> Res<Connection> {
    let conn = Connection::open(path).map_err(s)?;
    // Arayüz ve senkron ayrı bağlantılarla aynı dosyayı kullanır; WAL, birinin diğerini
    // beklemesini önler, meşgulse kısa süre bekletir.
    conn.execute_batch("PRAGMA journal_mode = WAL; PRAGMA busy_timeout = 5000;")
        .map_err(s)?;
    init(&conn)?;
    Ok(conn)
}

pub fn init(conn: &Connection) -> Res<()> {
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS tasks (
            id         TEXT PRIMARY KEY,
            title      TEXT NOT NULL,
            notes      TEXT NOT NULL DEFAULT '',
            color      TEXT NOT NULL DEFAULT 'blue',
            tags       TEXT NOT NULL DEFAULT '[]',
            deadline   TEXT NOT NULL DEFAULT '',
            recurring  INTEGER NOT NULL DEFAULT 0,
            recurrence TEXT,
            done       INTEGER NOT NULL DEFAULT 0,
            done_at    INTEGER,
            deleted    INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL,
            dirty      INTEGER NOT NULL DEFAULT 0
        );",
    )
    .map_err(s)
}

/// Tekrarlayan görevin "yapıldı" işareti, işaretlendiği dönem (gün/hafta)
/// bittiyse kendiliğinden geçersiz sayılır. Okuma anında hesaplanır.
fn effective_done(raw: &Raw, now_ms: i64, tz_offset_min: i64) -> bool {
    if !raw.done {
        return false;
    }
    if !raw.recurring {
        return true;
    }
    let Some(done_at) = raw.done_at else {
        return false;
    };
    let local_day = |ms: i64| (ms - tz_offset_min * 60_000).div_euclid(DAY_MS);
    let (a, n) = (local_day(done_at), local_day(now_ms));
    match raw.recurrence.as_deref() {
        // 1970-01-01 perşembe; +3 ile haftalar pazartesi başlar.
        Some("weekly") => (a + 3).div_euclid(7) == (n + 3).div_euclid(7),
        _ => a == n,
    }
}

fn to_task(raw: Raw, now_ms: i64, tz_offset_min: i64) -> Task {
    let done = effective_done(&raw, now_ms, tz_offset_min);
    Task {
        deadline_key: parse_deadline(&raw.deadline),
        tags: serde_json::from_str(&raw.tags).unwrap_or_default(),
        id: raw.id,
        title: raw.title,
        notes: raw.notes,
        color: raw.color,
        deadline: raw.deadline,
        recurring: raw.recurring,
        recurrence: raw.recurrence,
        done,
        done_at: raw.done_at,
        updated_at: raw.updated_at,
    }
}

fn rank(t: &Task) -> u8 {
    if t.recurring {
        0
    } else if !t.done {
        1
    } else {
        2
    }
}

fn by_deadline(a: &Task, b: &Task) -> Ordering {
    match (a.deadline_key, b.deadline_key) {
        (Some(x), Some(y)) => x.cmp(&y),
        (Some(_), None) => Ordering::Less,
        (None, Some(_)) => Ordering::Greater,
        (None, None) => Ordering::Equal,
    }
}

/// Sıra: tekrarlayanlar (ekleme sırasıyla), açık görevler (deadline'a göre,
/// deadline'sızlar ve okunamayanlar sonda), yapılanlar (en yeni üstte).
pub fn list(conn: &Connection, now_ms: i64, tz_offset_min: i64) -> Res<Vec<Task>> {
    let mut stmt = conn
        .prepare(&format!(
            "SELECT {COLS} FROM tasks WHERE deleted = 0 ORDER BY rowid"
        ))
        .map_err(s)?;
    let mut tasks = stmt
        .query_map([], raw_from_row)
        .map_err(s)?
        .map(|r| r.map(|raw| to_task(raw, now_ms, tz_offset_min)))
        .collect::<Result<Vec<_>, _>>()
        .map_err(s)?;

    tasks.sort_by(|a, b| {
        rank(a).cmp(&rank(b)).then_with(|| match rank(a) {
            1 => by_deadline(a, b),
            2 => b.done_at.cmp(&a.done_at),
            _ => Ordering::Equal,
        })
    });
    Ok(tasks)
}

fn normalize_recurrence(value: Option<&str>) -> String {
    match value {
        Some("weekly") => "weekly".into(),
        _ => "daily".into(),
    }
}

fn normalize_tags(tags: Vec<String>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for tag in tags {
        let tag = tag.trim().trim_start_matches('#').trim().to_string();
        if !tag.is_empty() && !out.contains(&tag) {
            out.push(tag);
        }
    }
    out
}

fn clean_title(title: &str) -> Res<String> {
    let title = title.trim();
    if title.is_empty() {
        Err("başlık boş olamaz".into())
    } else {
        Ok(title.to_string())
    }
}

pub fn add(conn: &Connection, new: NewTask, now_ms: i64) -> Res<String> {
    let title = clean_title(&new.title)?;
    let id = uuid::Uuid::new_v4().to_string();
    let recurrence = new
        .recurring
        .then(|| normalize_recurrence(new.recurrence.as_deref()));
    conn.execute(
        "INSERT INTO tasks (id, title, recurring, recurrence, updated_at, dirty)
         VALUES (?1, ?2, ?3, ?4, ?5, 1)",
        params![id, title, new.recurring, recurrence, now_ms],
    )
    .map_err(s)?;
    Ok(id)
}

pub fn update(conn: &Connection, id: &str, patch: TaskPatch, now_ms: i64) -> Res<()> {
    let mut raw = conn
        .query_row(
            &format!("SELECT {COLS} FROM tasks WHERE id = ?1 AND deleted = 0"),
            [id],
            raw_from_row,
        )
        .optional()
        .map_err(s)?
        .ok_or("görev bulunamadı")?;

    if let Some(title) = patch.title {
        raw.title = clean_title(&title)?;
    }
    if let Some(notes) = patch.notes {
        raw.notes = notes;
    }
    if let Some(color) = patch.color {
        raw.color = color;
    }
    if let Some(tags) = patch.tags {
        raw.tags = serde_json::to_string(&normalize_tags(tags)).map_err(s)?;
    }
    if let Some(deadline) = patch.deadline {
        raw.deadline = deadline.trim().to_string();
    }
    if let Some(recurring) = patch.recurring {
        raw.recurring = recurring;
    }
    if raw.recurring {
        raw.recurrence = Some(normalize_recurrence(
            patch.recurrence.as_deref().or(raw.recurrence.as_deref()),
        ));
        // Tekrarlayan görevlerde deadline yoktur.
        raw.deadline.clear();
    } else {
        raw.recurrence = None;
    }
    if let Some(done) = patch.done {
        raw.done = done;
        raw.done_at = done.then_some(now_ms);
    }

    conn.execute(
        "UPDATE tasks SET title = ?2, notes = ?3, color = ?4, tags = ?5, deadline = ?6,
                recurring = ?7, recurrence = ?8, done = ?9, done_at = ?10,
                updated_at = ?11, dirty = 1
         WHERE id = ?1",
        params![
            raw.id,
            raw.title,
            raw.notes,
            raw.color,
            raw.tags,
            raw.deadline,
            raw.recurring,
            raw.recurrence,
            raw.done,
            raw.done_at,
            now_ms
        ],
    )
    .map_err(s)?;
    Ok(())
}

/// Silme, görevi kaldırmaz; "silindi" işaretiyle (tombstone) saklar.
pub fn delete(conn: &Connection, id: &str, now_ms: i64) -> Res<()> {
    let changed = conn
        .execute(
            "UPDATE tasks SET deleted = 1, updated_at = ?2, dirty = 1
             WHERE id = ?1 AND deleted = 0",
            params![id, now_ms],
        )
        .map_err(s)?;
    if changed == 0 {
        return Err("görev bulunamadı".into());
    }
    Ok(())
}

/// Henüz senkronlanmamış (yerelde değişmiş) görev sayısı.
pub fn pending_count(conn: &Connection) -> Res<i64> {
    conn.query_row("SELECT COUNT(*) FROM tasks WHERE dirty = 1", [], |r| r.get(0))
        .map_err(s)
}

const SYNC_COLS: &str =
    "id, title, notes, color, tags, deadline, recurring, recurrence, done, done_at, deleted, updated_at";

fn sync_task_from_row(r: &Row) -> rusqlite::Result<SyncTask> {
    let tags: String = r.get(4)?;
    Ok(SyncTask {
        id: r.get(0)?,
        title: r.get(1)?,
        notes: r.get(2)?,
        color: r.get(3)?,
        tags: serde_json::from_str(&tags).unwrap_or_default(),
        deadline: r.get(5)?,
        recurring: r.get(6)?,
        recurrence: r.get(7)?,
        done: r.get(8)?,
        done_at: r.get(9)?,
        deleted: r.get(10)?,
        updated_at: r.get(11)?,
    })
}

/// Senkron için silinenler (tombstone) dahil tüm satırlar.
pub fn snapshot(conn: &Connection) -> Res<Vec<SyncTask>> {
    let mut stmt = conn
        .prepare(&format!("SELECT {SYNC_COLS} FROM tasks ORDER BY rowid"))
        .map_err(s)?;
    let rows = stmt.query_map([], sync_task_from_row).map_err(s)?;
    rows.collect::<Result<Vec<_>, _>>().map_err(s)
}

/// Uzaktan gelen görevleri yerele yazar. `base`, birleştirmenin dayandığı `snapshot`tır:
/// kullanıcı o andan sonra bir görevi değiştirdiyse (updated_at farklıysa) o satıra
/// dokunulmaz, değişiklik kaybolmaz ve bir sonraki senkronda gider.
pub fn apply_remote(conn: &Connection, incoming: &[SyncTask], base: &[SyncTask]) -> Res<()> {
    let tx = conn.unchecked_transaction().map_err(s)?;
    for t in incoming {
        let tags = serde_json::to_string(&t.tags).map_err(s)?;
        match base.iter().find(|b| b.id == t.id) {
            None => tx.execute(
                "INSERT OR IGNORE INTO tasks
                    (id, title, notes, color, tags, deadline, recurring, recurrence,
                     done, done_at, deleted, updated_at, dirty)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, 0)",
                params![
                    t.id, t.title, t.notes, t.color, tags, t.deadline, t.recurring,
                    t.recurrence, t.done, t.done_at, t.deleted, t.updated_at
                ],
            ),
            Some(b) => tx.execute(
                "UPDATE tasks SET title = ?2, notes = ?3, color = ?4, tags = ?5, deadline = ?6,
                        recurring = ?7, recurrence = ?8, done = ?9, done_at = ?10,
                        deleted = ?11, updated_at = ?12, dirty = 0
                 WHERE id = ?1 AND updated_at = ?13",
                params![
                    t.id, t.title, t.notes, t.color, tags, t.deadline, t.recurring,
                    t.recurrence, t.done, t.done_at, t.deleted, t.updated_at, b.updated_at
                ],
            ),
        }
        .map_err(s)?;
    }
    tx.commit().map_err(s)
}

/// Başarılı senkrondan sonra çağrılır: birleşik sürümle aynı `updated_at`'e sahip satırların
/// "bekleyen" işaretini kaldırır. Senkron sürerken değiştirilen satırlar bekleyen kalır.
pub fn mark_synced(conn: &Connection, merged: &[SyncTask]) -> Res<()> {
    let tx = conn.unchecked_transaction().map_err(s)?;
    for t in merged {
        tx.execute(
            "UPDATE tasks SET dirty = 0 WHERE id = ?1 AND updated_at = ?2",
            params![t.id, t.updated_at],
        )
        .map_err(s)?;
    }
    tx.commit().map_err(s)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn db() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        init(&conn).unwrap();
        conn
    }

    fn ms(text: &str) -> i64 {
        parse_deadline(text).unwrap()
    }

    fn new(title: &str) -> NewTask {
        NewTask { title: title.into(), recurring: false, recurrence: None }
    }

    fn recurring(title: &str, recurrence: &str) -> NewTask {
        NewTask { title: title.into(), recurring: true, recurrence: Some(recurrence.into()) }
    }

    fn with_deadline(text: &str) -> TaskPatch {
        TaskPatch { deadline: Some(text.into()), ..Default::default() }
    }

    fn done(value: bool) -> TaskPatch {
        TaskPatch { done: Some(value), ..Default::default() }
    }

    fn titles(tasks: &[Task]) -> Vec<&str> {
        tasks.iter().map(|t| t.title.as_str()).collect()
    }

    const NOW: i64 = 1_000;

    #[test]
    fn ekle_ve_listele() {
        let conn = db();
        let id = add(&conn, new("  süt al  "), NOW).unwrap();
        let tasks = list(&conn, NOW, 0).unwrap();
        assert_eq!(tasks.len(), 1);
        assert_eq!(tasks[0].id, id);
        assert_eq!(tasks[0].title, "süt al");
        assert!(!tasks[0].done && !tasks[0].recurring);
        assert_eq!(tasks[0].color, "blue");
    }

    #[test]
    fn bos_baslik_reddedilir() {
        let conn = db();
        assert!(add(&conn, new("   "), NOW).is_err());
        let id = add(&conn, new("a"), NOW).unwrap();
        let patch = TaskPatch { title: Some(" ".into()), ..Default::default() };
        assert!(update(&conn, &id, patch, NOW).is_err());
    }

    #[test]
    fn siralama_tekrarlayan_acik_yapilan() {
        let conn = db();
        let sonra = add(&conn, new("sonra"), NOW).unwrap();
        let once = add(&conn, new("önce"), NOW).unwrap();
        let _tarihsiz = add(&conn, new("tarihsiz"), NOW).unwrap();
        let bitti = add(&conn, new("bitti"), NOW).unwrap();
        let _gunluk = add(&conn, recurring("günlük", "daily"), NOW).unwrap();

        update(&conn, &sonra, with_deadline("20.12.2026 10:00"), NOW).unwrap();
        update(&conn, &once, with_deadline("01.12.2026 10:00"), NOW).unwrap();
        update(&conn, &bitti, done(true), NOW).unwrap();

        let tasks = list(&conn, NOW, 0).unwrap();
        assert_eq!(titles(&tasks), ["günlük", "önce", "sonra", "tarihsiz", "bitti"]);
    }

    #[test]
    fn okunamayan_deadline_metni_korunur_ve_sonda_siralanir() {
        let conn = db();
        let a = add(&conn, new("yarın yap"), NOW).unwrap();
        let b = add(&conn, new("tarihli"), NOW).unwrap();
        update(&conn, &a, with_deadline("yarın akşam"), NOW).unwrap();
        update(&conn, &b, with_deadline("05.05.2027"), NOW).unwrap();

        let tasks = list(&conn, NOW, 0).unwrap();
        assert_eq!(titles(&tasks), ["tarihli", "yarın yap"]);
        assert_eq!(tasks[1].deadline, "yarın akşam");
        assert_eq!(tasks[1].deadline_key, None);
        assert!(tasks[0].deadline_key.is_some());
    }

    #[test]
    fn yapilanlar_en_yeni_ustte() {
        let conn = db();
        let a = add(&conn, new("a"), NOW).unwrap();
        let b = add(&conn, new("b"), NOW).unwrap();
        update(&conn, &a, done(true), 100).unwrap();
        update(&conn, &b, done(true), 200).unwrap();
        assert_eq!(titles(&list(&conn, 300, 0).unwrap()), ["b", "a"]);
    }

    #[test]
    fn yapildi_geri_alinabilir() {
        let conn = db();
        let id = add(&conn, new("a"), NOW).unwrap();
        update(&conn, &id, done(true), NOW).unwrap();
        update(&conn, &id, done(false), NOW).unwrap();
        let t = &list(&conn, NOW, 0).unwrap()[0];
        assert!(!t.done);
        assert_eq!(t.done_at, None);
    }

    #[test]
    fn gunluk_tekrarlayan_ertesi_gun_sifirlanir() {
        let conn = db();
        let id = add(&conn, recurring("su iç", "daily"), NOW).unwrap();
        update(&conn, &id, done(true), ms("07.10.2026 12:00")).unwrap();

        let ayni_gun = list(&conn, ms("07.10.2026 23:59"), 0).unwrap();
        assert!(ayni_gun[0].done);
        let ertesi_gun = list(&conn, ms("08.10.2026 00:00"), 0).unwrap();
        assert!(!ertesi_gun[0].done);
    }

    #[test]
    fn haftalik_tekrarlayan_pazartesi_sifirlanir() {
        let conn = db();
        let id = add(&conn, recurring("çamaşır", "weekly"), NOW).unwrap();
        // 07.10.2026 çarşamba
        update(&conn, &id, done(true), ms("07.10.2026 12:00")).unwrap();

        assert!(list(&conn, ms("11.10.2026 23:00"), 0).unwrap()[0].done); // pazar
        assert!(!list(&conn, ms("12.10.2026 00:00"), 0).unwrap()[0].done); // pazartesi
    }

    #[test]
    fn tekrarlayan_sifirlama_yerel_saat_dilimine_gore() {
        let conn = db();
        let id = add(&conn, recurring("a", "daily"), NOW).unwrap();
        // UTC+3: UTC 07.10 22:00 = yerel 08.10 01:00
        update(&conn, &id, done(true), ms("07.10.2026 22:00")).unwrap();
        let tz = -180;
        assert!(list(&conn, ms("08.10.2026 10:00"), tz).unwrap()[0].done);
        assert!(!list(&conn, ms("08.10.2026 21:00"), tz).unwrap()[0].done); // yerel 09.10 00:00
    }

    #[test]
    fn tekrarlayan_gorevde_deadline_olmaz() {
        let conn = db();
        let id = add(&conn, new("a"), NOW).unwrap();
        update(&conn, &id, with_deadline("01.01.2027"), NOW).unwrap();
        let patch = TaskPatch { recurring: Some(true), ..Default::default() };
        update(&conn, &id, patch, NOW).unwrap();

        let t = &list(&conn, NOW, 0).unwrap()[0];
        assert!(t.recurring);
        assert_eq!(t.recurrence.as_deref(), Some("daily"));
        assert_eq!(t.deadline, "");

        update(&conn, &id, with_deadline("02.02.2027"), NOW).unwrap();
        assert_eq!(list(&conn, NOW, 0).unwrap()[0].deadline, "");
    }

    #[test]
    fn tekrarlamayi_kapatinca_periyot_silinir() {
        let conn = db();
        let id = add(&conn, recurring("a", "weekly"), NOW).unwrap();
        let patch = TaskPatch { recurring: Some(false), ..Default::default() };
        update(&conn, &id, patch, NOW).unwrap();
        let t = &list(&conn, NOW, 0).unwrap()[0];
        assert!(!t.recurring);
        assert_eq!(t.recurrence, None);
    }

    #[test]
    fn etiketler_temizlenir() {
        let conn = db();
        let id = add(&conn, new("a"), NOW).unwrap();
        let tags = vec!["#iş".into(), " iş ".into(), "".into(), "ev".into()];
        let patch = TaskPatch { tags: Some(tags), ..Default::default() };
        update(&conn, &id, patch, NOW).unwrap();
        assert_eq!(list(&conn, NOW, 0).unwrap()[0].tags, ["iş", "ev"]);
    }

    #[test]
    fn silinen_gorev_listede_yok_ama_bekleyen_sayilir() {
        let conn = db();
        let id = add(&conn, new("a"), NOW).unwrap();
        delete(&conn, &id, NOW).unwrap();
        assert!(list(&conn, NOW, 0).unwrap().is_empty());
        // tombstone satırı duruyor
        let sayi: i64 = conn
            .query_row("SELECT COUNT(*) FROM tasks WHERE deleted = 1", [], |r| r.get(0))
            .unwrap();
        assert_eq!(sayi, 1);
        assert!(delete(&conn, &id, NOW).is_err());
        assert!(update(&conn, &id, done(true), NOW).is_err());
    }

    #[test]
    fn bekleyen_degisiklik_sayisi() {
        let conn = db();
        assert_eq!(pending_count(&conn).unwrap(), 0);
        let a = add(&conn, new("a"), NOW).unwrap();
        add(&conn, new("b"), NOW).unwrap();
        update(&conn, &a, done(true), NOW).unwrap(); // aynı görev, hâlâ 2 görev
        assert_eq!(pending_count(&conn).unwrap(), 2);
    }

    fn remote_task(id: &str, title: &str, updated_at: i64) -> SyncTask {
        SyncTask {
            id: id.into(),
            title: title.into(),
            notes: String::new(),
            color: "blue".into(),
            tags: vec![],
            deadline: String::new(),
            recurring: false,
            recurrence: None,
            done: false,
            done_at: None,
            deleted: false,
            updated_at,
        }
    }

    #[test]
    fn snapshot_silinenleri_de_icerir() {
        let conn = db();
        let a = add(&conn, new("a"), NOW).unwrap();
        add(&conn, new("b"), NOW).unwrap();
        delete(&conn, &a, 500).unwrap();
        let snap = snapshot(&conn).unwrap();
        assert_eq!(snap.len(), 2);
        let silinen = snap.iter().find(|t| t.id == a).unwrap();
        assert!(silinen.deleted);
        assert_eq!(silinen.updated_at, 500);
    }

    #[test]
    fn uzaktan_gelen_yeni_gorev_eklenir_bekleyen_olmaz() {
        let conn = db();
        apply_remote(&conn, &[remote_task("r1", "uzak", 100)], &[]).unwrap();
        assert_eq!(titles(&list(&conn, NOW, 0).unwrap()), ["uzak"]);
        assert_eq!(pending_count(&conn).unwrap(), 0);
    }

    #[test]
    fn uzaktan_gelen_guncelleme_degismemis_satira_yazilir() {
        let conn = db();
        let id = add(&conn, new("eski"), 100).unwrap();
        let base = snapshot(&conn).unwrap();
        apply_remote(&conn, &[remote_task(&id, "yeni", 300)], &base).unwrap();
        let t = &list(&conn, NOW, 0).unwrap()[0];
        assert_eq!(t.title, "yeni");
        assert_eq!(t.updated_at, 300);
        assert_eq!(pending_count(&conn).unwrap(), 0);
    }

    #[test]
    fn senkron_sirasinda_degisen_satira_dokunulmaz() {
        let conn = db();
        let id = add(&conn, new("eski"), 100).unwrap();
        let base = snapshot(&conn).unwrap();
        // senkron sürerken kullanıcı görevi düzenledi
        let edit = TaskPatch { title: Some("kullanıcı".into()), ..Default::default() };
        update(&conn, &id, edit, 200).unwrap();
        apply_remote(&conn, &[remote_task(&id, "uzak", 300)], &base).unwrap();
        let t = &list(&conn, NOW, 0).unwrap()[0];
        assert_eq!(t.title, "kullanıcı");
        assert_eq!(pending_count(&conn).unwrap(), 1);
    }

    #[test]
    fn uzaktan_silinmis_gorev_yerelde_gizlenir() {
        let conn = db();
        let id = add(&conn, new("a"), 100).unwrap();
        let base = snapshot(&conn).unwrap();
        let mut gone = remote_task(&id, "a", 300);
        gone.deleted = true;
        apply_remote(&conn, &[gone], &base).unwrap();
        assert!(list(&conn, NOW, 0).unwrap().is_empty());
    }

    #[test]
    fn mark_synced_yalnizca_ayni_surumu_temizler() {
        let conn = db();
        add(&conn, new("a"), 100).unwrap();
        let b = add(&conn, new("b"), 100).unwrap();
        let merged = snapshot(&conn).unwrap();
        // senkron sürerken b değişti
        update(&conn, &b, done(true), 200).unwrap();
        mark_synced(&conn, &merged).unwrap();
        assert_eq!(pending_count(&conn).unwrap(), 1);
        let kalan: String = conn
            .query_row("SELECT id FROM tasks WHERE dirty = 1", [], |r| r.get(0))
            .unwrap();
        assert_eq!(kalan, b);
    }
}

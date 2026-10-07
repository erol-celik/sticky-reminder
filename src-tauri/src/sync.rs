use std::collections::HashMap;

use serde::{Deserialize, Serialize};

/// Drive'daki `sticky.json` dosyasının biçim sürümü. Daha yeni bir sürüm okunursa dosya
/// bozulmasın diye senkron durur.
pub const FORMAT_VERSION: u32 = 1;

fn default_color() -> String {
    "blue".into()
}

/// Senkronda taşınan görev: silinenler (tombstone) dahil tüm alanlar. Android tarafındaki
/// `SyncTask` ile aynı JSON biçimini üretir.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncTask {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub notes: String,
    #[serde(default = "default_color")]
    pub color: String,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub deadline: String,
    #[serde(default)]
    pub recurring: bool,
    #[serde(default)]
    pub recurrence: Option<String>,
    #[serde(default)]
    pub done: bool,
    #[serde(default)]
    pub done_at: Option<i64>,
    #[serde(default)]
    pub deleted: bool,
    pub updated_at: i64,
}

#[derive(Serialize, Deserialize)]
struct SyncFile {
    version: u32,
    tasks: Vec<SyncTask>,
}

pub fn encode(tasks: &[SyncTask]) -> Result<String, String> {
    serde_json::to_string(&SyncFile { version: FORMAT_VERSION, tasks: tasks.to_vec() })
        .map_err(|e| e.to_string())
}

pub fn decode(json: &str) -> Result<Vec<SyncTask>, String> {
    let file: SyncFile = serde_json::from_str(json).map_err(|e| format!("sticky.json okunamadı: {e}"))?;
    if file.version > FORMAT_VERSION {
        return Err(format!(
            "sticky.json daha yeni bir sürümden (v{}); bu uygulamayı güncelle",
            file.version
        ));
    }
    Ok(file.tasks)
}

#[derive(Debug)]
pub struct MergeResult {
    /// Birleşik liste: yerelin sırası, ardından yalnızca uzakta olanlar.
    pub merged: Vec<SyncTask>,
    /// Birleşik sonuç uzaktaki dosyadan farklıysa yüklenmeli.
    pub upload_needed: bool,
    /// Yerel veritabanına yazılması gerekenler (yerelden farklı olanlar).
    pub apply_local: Vec<SyncTask>,
}

/// Görev bazında birleştirme: en yeni `updated_at` kazanır. Eşitlikte silinmiş olan kazanır,
/// o da değilse uzaktaki sürüm kazanır (uzaktaki dosya ortak durumdur, böylece iki cihaz
/// aynı sonuca yakınsar).
pub fn merge(local: &[SyncTask], remote: &[SyncTask]) -> MergeResult {
    let local_by_id: HashMap<&str, &SyncTask> = local.iter().map(|t| (t.id.as_str(), t)).collect();
    let remote_by_id: HashMap<&str, &SyncTask> = remote.iter().map(|t| (t.id.as_str(), t)).collect();

    let mut merged: Vec<SyncTask> = Vec::with_capacity(local.len().max(remote.len()));
    for l in local {
        let winner = match remote_by_id.get(l.id.as_str()) {
            None => l,
            Some(r) => pick(l, r),
        };
        merged.push(winner.clone());
    }
    for r in remote {
        if !local_by_id.contains_key(r.id.as_str()) {
            merged.push(r.clone());
        }
    }

    let upload_needed = merged.iter().any(|m| remote_by_id.get(m.id.as_str()) != Some(&m));
    let apply_local = merged
        .iter()
        .filter(|m| local_by_id.get(m.id.as_str()) != Some(m))
        .cloned()
        .collect();
    MergeResult { merged, upload_needed, apply_local }
}

fn pick<'a>(local: &'a SyncTask, remote: &'a SyncTask) -> &'a SyncTask {
    use std::cmp::Ordering::*;
    match local.updated_at.cmp(&remote.updated_at) {
        Greater => local,
        Less => remote,
        Equal if local.deleted && !remote.deleted => local,
        Equal => remote,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Deserialize)]
    struct Cases {
        cases: Vec<Case>,
    }

    #[derive(Deserialize)]
    struct Case {
        name: String,
        local: Vec<SyncTask>,
        remote: Vec<SyncTask>,
        expect: Expect,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Expect {
        merged: Vec<SyncTask>,
        upload_needed: bool,
        apply_local: Vec<String>,
    }

    fn by_id(mut tasks: Vec<SyncTask>) -> Vec<SyncTask> {
        tasks.sort_by(|a, b| a.id.cmp(&b.id));
        tasks
    }

    #[test]
    fn paylasilan_birlestirme_senaryolari() {
        let all: Cases = serde_json::from_str(include_str!("../../shared/sync-cases.json")).unwrap();
        assert!(all.cases.len() >= 10);
        for case in all.cases {
            let result = merge(&case.local, &case.remote);
            assert_eq!(by_id(result.merged), by_id(case.expect.merged), "{}: merged", case.name);
            assert_eq!(result.upload_needed, case.expect.upload_needed, "{}: uploadNeeded", case.name);
            let mut applied: Vec<String> = result.apply_local.into_iter().map(|t| t.id).collect();
            applied.sort();
            let mut expected = case.expect.apply_local;
            expected.sort();
            assert_eq!(applied, expected, "{}: applyLocal", case.name);
        }
    }

    fn task(id: &str, updated_at: i64) -> SyncTask {
        SyncTask {
            id: id.into(),
            title: id.into(),
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
    fn birlestirme_iki_yonde_ayni_sonuca_yakinsar() {
        // A cihazı ve B cihazı aynı iki sürümü farklı yönlerden birleştirse de sonuç aynı olmalı.
        let a = vec![task("x", 200), task("y", 100)];
        let b = vec![task("x", 100), task("z", 300)];
        let from_a = merge(&a, &b).merged;
        let from_b = merge(&b, &a).merged;
        assert_eq!(by_id(from_a), by_id(from_b));
    }

    #[test]
    fn birlestirme_ikinci_kez_degisiklik_gerektirmez() {
        let local = vec![task("x", 200), task("y", 100)];
        let remote = vec![task("x", 100), task("z", 300)];
        let first = merge(&local, &remote);
        let second = merge(&first.merged, &first.merged);
        assert!(!second.upload_needed);
        assert!(second.apply_local.is_empty());
    }

    #[test]
    fn json_gidis_donus() {
        let mut t = task("a", 5);
        t.tags = vec!["ev".into(), "sağlık".into()];
        t.recurring = true;
        t.recurrence = Some("weekly".into());
        t.done = true;
        t.done_at = Some(7);
        let json = encode(&[t.clone()]).unwrap();
        assert!(json.contains("\"updatedAt\":5") && json.contains("\"doneAt\":7"));
        assert_eq!(decode(&json).unwrap(), vec![t]);
    }

    #[test]
    fn daha_yeni_surum_reddedilir() {
        let err = decode(r#"{"version":2,"tasks":[]}"#).unwrap_err();
        assert!(err.contains("daha yeni"), "{err}");
    }

    #[test]
    fn eksik_ve_bilinmeyen_alanlar_hosgorulur() {
        let tasks = decode(
            r#"{"version":1,"tasks":[{"id":"a","title":"t","updatedAt":1,"yeniAlan":42}],"baska":true}"#,
        )
        .unwrap();
        assert_eq!(tasks[0].color, "blue");
        assert!(!tasks[0].deleted && tasks[0].tags.is_empty());
    }

    #[test]
    fn bozuk_json_hata_verir() {
        assert!(decode("değil").is_err());
        assert!(decode(r#"{"version":1}"#).is_err());
    }
}

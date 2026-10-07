//! Senkron akışı: Drive'daki dosyayı indir, yerelle birleştir, gerekirse geri yükle.
//! Ağ katmanı `Remote` arkasında durur; testlerde bellek içi sahte bir uzak kullanılır.

use rusqlite::Connection;

use crate::store::{self, Res};
use crate::sync;

/// Yükleme öncesi dosya başkası tarafından değişirse birleştirme bu kadar tekrarlanır.
const MAX_ATTEMPTS: usize = 3;

pub struct RemoteFile {
    pub id: String,
    pub version: String,
    pub content: String,
}

pub trait Remote {
    /// Dosyayı indirir; yoksa `None`. `version`, içeriğin en az o sürümden yeni olduğunu garanti eder.
    fn fetch(&mut self) -> Res<Option<RemoteFile>>;
    /// Dosyanın şu anki sürümü; dosya yoksa `None`. Yükleme öncesi yarış kontrolü içindir.
    fn current_version(&mut self, known_id: Option<&str>) -> Res<Option<String>>;
    /// Dosyayı oluşturur (`existing_id` yoksa) ya da günceller; yeni sürümü döndürür.
    fn upload(&mut self, existing_id: Option<&str>, content: &str) -> Res<String>;
}

#[derive(Debug, PartialEq)]
pub struct Outcome {
    /// Uzaktan gelip yerele yazılan görev sayısı.
    pub pulled: usize,
    pub uploaded: bool,
}

pub fn run(conn: &Connection, remote: &mut dyn Remote) -> Res<Outcome> {
    let mut pulled_total = 0;
    for _ in 0..MAX_ATTEMPTS {
        let file = remote.fetch()?;
        let remote_tasks = match &file {
            Some(f) => sync::decode(&f.content)?,
            None => Vec::new(),
        };
        let local = store::snapshot(conn)?;
        let merge = sync::merge(&local, &remote_tasks);

        store::apply_remote(conn, &merge.apply_local, &local)?;
        pulled_total += merge.apply_local.len();

        if merge.upload_needed {
            // Dosya, indirdiğimizden beri değiştiyse üzerine yazmayız; yeniden birleştiririz.
            let expected = file.as_ref().map(|f| f.version.clone());
            let known_id = file.as_ref().map(|f| f.id.as_str());
            if remote.current_version(known_id)? != expected {
                continue;
            }
            remote.upload(known_id, &sync::encode(&merge.merged)?)?;
        }

        store::mark_synced(conn, &merge.merged)?;
        return Ok(Outcome { pulled: pulled_total, uploaded: merge.upload_needed });
    }
    Err("Drive dosyası sürekli değişiyor; birazdan yeniden denenecek".into())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::{NewTask, TaskPatch};

    /// Drive'ın küçük bir taklidi: tek dosya, artan sürüm numarası.
    #[derive(Default)]
    struct FakeRemote {
        file: Option<(String, u64, String)>, // (id, sürüm, içerik)
        uploads: usize,
        fail_upload: bool,
        fail_fetch: bool,
        /// fetch ile yükleme kontrolü arasında başka bir cihaz dosyayı bu kadar kez değiştirir.
        interfere: usize,
        interfering_content: Option<String>,
    }

    impl FakeRemote {
        fn write(&mut self, content: String) -> String {
            let id = self.file.as_ref().map(|f| f.0.clone()).unwrap_or_else(|| "file-1".into());
            let version = self.file.as_ref().map(|f| f.1 + 1).unwrap_or(1);
            self.file = Some((id, version, content));
            version.to_string()
        }
    }

    impl Remote for FakeRemote {
        fn fetch(&mut self) -> Res<Option<RemoteFile>> {
            if self.fail_fetch {
                return Err("Ağ hatası: bağlantı yok".into());
            }
            Ok(self.file.as_ref().map(|(id, v, c)| RemoteFile {
                id: id.clone(),
                version: v.to_string(),
                content: c.clone(),
            }))
        }

        fn current_version(&mut self, _known_id: Option<&str>) -> Res<Option<String>> {
            if self.interfere > 0 {
                self.interfere -= 1;
                let content = self.interfering_content.clone().unwrap_or_else(|| sync::encode(&[]).unwrap());
                self.write(content);
            }
            Ok(self.file.as_ref().map(|f| f.1.to_string()))
        }

        fn upload(&mut self, _existing_id: Option<&str>, content: &str) -> Res<String> {
            if self.fail_upload {
                return Err("Drive 500: hata".into());
            }
            self.uploads += 1;
            Ok(self.write(content.to_string()))
        }
    }

    fn db() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        store::init(&conn).unwrap();
        conn
    }

    fn add(conn: &Connection, title: &str, at: i64) -> String {
        store::add(conn, NewTask { title: title.into(), recurring: false, recurrence: None }, at).unwrap()
    }

    fn titles(conn: &Connection) -> Vec<String> {
        let mut t: Vec<String> = store::list(conn, 0, 0).unwrap().into_iter().map(|t| t.title).collect();
        t.sort();
        t
    }

    #[test]
    fn ilk_senkron_dosyayi_olusturur_ve_bekleyenleri_temizler() {
        let (conn, mut remote) = (db(), FakeRemote::default());
        add(&conn, "a", 100);
        add(&conn, "b", 100);
        assert_eq!(store::pending_count(&conn).unwrap(), 2);

        let out = run(&conn, &mut remote).unwrap();
        assert_eq!(out, Outcome { pulled: 0, uploaded: true });
        assert_eq!(remote.uploads, 1);
        assert_eq!(store::pending_count(&conn).unwrap(), 0);
        assert_eq!(sync::decode(&remote.file.as_ref().unwrap().2).unwrap().len(), 2);
    }

    #[test]
    fn degisiklik_yoksa_yukleme_yapilmaz() {
        let (conn, mut remote) = (db(), FakeRemote::default());
        add(&conn, "a", 100);
        run(&conn, &mut remote).unwrap();
        let again = run(&conn, &mut remote).unwrap();
        assert_eq!(again, Outcome { pulled: 0, uploaded: false });
        assert_eq!(remote.uploads, 1);
    }

    #[test]
    fn bos_iki_taraf_dosya_olusturmaz() {
        let (conn, mut remote) = (db(), FakeRemote::default());
        let out = run(&conn, &mut remote).unwrap();
        assert_eq!(out, Outcome { pulled: 0, uploaded: false });
        assert!(remote.file.is_none());
    }

    #[test]
    fn ikinci_cihaz_gorevleri_ceker_yukleme_yapmaz() {
        let mut remote = FakeRemote::default();
        let (a, b) = (db(), db());
        add(&a, "telefondan", 100);
        run(&a, &mut remote).unwrap();

        let out = run(&b, &mut remote).unwrap();
        assert_eq!(out, Outcome { pulled: 1, uploaded: false });
        assert_eq!(titles(&b), ["telefondan"]);
        assert_eq!(store::pending_count(&b).unwrap(), 0);
        assert_eq!(remote.uploads, 1);
    }

    #[test]
    fn iki_cihaz_birbirinin_eklemelerini_gorur() {
        let mut remote = FakeRemote::default();
        let (a, b) = (db(), db());
        add(&a, "a-gorevi", 100);
        run(&a, &mut remote).unwrap();
        add(&b, "b-gorevi", 200);
        run(&b, &mut remote).unwrap();
        run(&a, &mut remote).unwrap();
        assert_eq!(titles(&a), ["a-gorevi", "b-gorevi"]);
        assert_eq!(titles(&b), ["a-gorevi", "b-gorevi"]);
    }

    #[test]
    fn silme_diger_cihaza_yayilir_ve_geri_gelmez() {
        let mut remote = FakeRemote::default();
        let (a, b) = (db(), db());
        let id = add(&a, "silinecek", 100);
        run(&a, &mut remote).unwrap();
        run(&b, &mut remote).unwrap();
        assert_eq!(titles(&b), ["silinecek"]);

        store::delete(&a, &id, 300).unwrap();
        run(&a, &mut remote).unwrap();
        run(&b, &mut remote).unwrap();
        assert!(titles(&b).is_empty());
        // b yeniden senkronlasa da görev geri dirilmez
        run(&b, &mut remote).unwrap();
        run(&a, &mut remote).unwrap();
        assert!(titles(&a).is_empty() && titles(&b).is_empty());
    }

    #[test]
    fn cakisan_duzenlemede_en_yeni_kazanir() {
        let mut remote = FakeRemote::default();
        let (a, b) = (db(), db());
        let id = add(&a, "ilk", 100);
        run(&a, &mut remote).unwrap();
        run(&b, &mut remote).unwrap();

        let rename = |t: &str| TaskPatch { title: Some(t.into()), ..Default::default() };
        store::update(&a, &id, rename("a-surumu"), 300).unwrap();
        store::update(&b, &id, rename("b-surumu"), 400).unwrap(); // daha yeni
        run(&a, &mut remote).unwrap();
        run(&b, &mut remote).unwrap();
        run(&a, &mut remote).unwrap();
        assert_eq!(titles(&a), ["b-surumu"]);
        assert_eq!(titles(&b), ["b-surumu"]);
    }

    #[test]
    fn dosya_arada_degisirse_yeniden_birlestirir() {
        let mut remote = FakeRemote::default();
        let (a, other) = (db(), db());
        add(&a, "yerel", 100);

        // Başka bir cihaz, bizim indirmemizle yüklememiz arasında kendi görevini yükler.
        add(&other, "diger-cihaz", 150);
        let other_tasks = store::snapshot(&other).unwrap();
        remote.interfere = 1;
        remote.interfering_content = Some(sync::encode(&other_tasks).unwrap());

        let out = run(&a, &mut remote).unwrap();
        assert!(out.uploaded);
        assert_eq!(titles(&a), ["diger-cihaz", "yerel"]);
        // Uzaktaki dosyada ikisi de var: başkasının yüklemesi ezilmedi.
        let remote_titles: Vec<String> =
            sync::decode(&remote.file.as_ref().unwrap().2).unwrap().into_iter().map(|t| t.title).collect();
        assert!(remote_titles.contains(&"yerel".to_string()));
        assert!(remote_titles.contains(&"diger-cihaz".to_string()));
        assert_eq!(store::pending_count(&a).unwrap(), 0);
    }

    #[test]
    fn dosya_surekli_degisirse_hata_verir_ve_bekleyenler_kalir() {
        let mut remote = FakeRemote { interfere: 10, ..Default::default() };
        let conn = db();
        add(&conn, "a", 100);
        let err = run(&conn, &mut remote).unwrap_err();
        assert!(err.contains("sürekli değişiyor"), "{err}");
        assert_eq!(remote.uploads, 0);
        assert_eq!(store::pending_count(&conn).unwrap(), 1);
    }

    #[test]
    fn yukleme_hatasinda_degisiklikler_bekleyen_kalir() {
        let mut remote = FakeRemote { fail_upload: true, ..Default::default() };
        let conn = db();
        add(&conn, "a", 100);
        assert!(run(&conn, &mut remote).is_err());
        assert_eq!(store::pending_count(&conn).unwrap(), 1);

        remote.fail_upload = false;
        run(&conn, &mut remote).unwrap();
        assert_eq!(store::pending_count(&conn).unwrap(), 0);
    }

    #[test]
    fn ag_hatasinda_yerel_veri_bozulmaz() {
        let mut remote = FakeRemote { fail_fetch: true, ..Default::default() };
        let conn = db();
        add(&conn, "a", 100);
        let err = run(&conn, &mut remote).unwrap_err();
        assert!(err.contains("Ağ hatası"));
        assert_eq!(titles(&conn), ["a"]);
        assert_eq!(store::pending_count(&conn).unwrap(), 1);
    }

    #[test]
    fn daha_yeni_surumlu_dosyaya_dokunulmaz() {
        let mut remote = FakeRemote::default();
        remote.write(r#"{"version":2,"tasks":[]}"#.to_string());
        let conn = db();
        add(&conn, "a", 100);
        let err = run(&conn, &mut remote).unwrap_err();
        assert!(err.contains("daha yeni"), "{err}");
        assert_eq!(remote.uploads, 0);
        assert_eq!(remote.file.as_ref().unwrap().2, r#"{"version":2,"tasks":[]}"#);
    }

    #[test]
    fn bozuk_dosya_uzerine_yazilmaz() {
        let mut remote = FakeRemote::default();
        remote.write("bozuk içerik".to_string());
        let conn = db();
        add(&conn, "a", 100);
        assert!(run(&conn, &mut remote).is_err());
        assert_eq!(remote.uploads, 0);
        assert_eq!(remote.file.as_ref().unwrap().2, "bozuk içerik");
    }
}

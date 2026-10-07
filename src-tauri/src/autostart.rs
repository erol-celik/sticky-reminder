//! Windows oturum açılışında otomatik başlama: HKCU altındaki `Run` anahtarında tek değer.
//! Yönetici izni gerekmez, yalnızca bu kullanıcı için geçerlidir.

use winreg::enums::{HKEY_CURRENT_USER, KEY_READ, KEY_WRITE};
use winreg::RegKey;

use crate::store::Res;

const RUN_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";
const VALUE_NAME: &str = "Sticky";

fn s<E: ToString>(e: E) -> String {
    e.to_string()
}

/// Kayıt defterine yazılacak komut: yolun boşluk içerebilmesi için tırnaklı.
pub fn command_for(exe: &std::path::Path) -> String {
    format!("\"{}\"", exe.display())
}

pub fn is_enabled() -> bool {
    is_enabled_in(RUN_KEY, VALUE_NAME)
}

/// Açıkken şu anki çalıştırılabilir dosyanın yolunu yazar (taşınmış/yeniden kurulmuşsa onarır).
pub fn set_enabled(enabled: bool) -> Res<()> {
    let exe = std::env::current_exe().map_err(s)?;
    set_enabled_in(RUN_KEY, VALUE_NAME, enabled, &command_for(&exe))
}

fn is_enabled_in(key: &str, name: &str) -> bool {
    RegKey::predef(HKEY_CURRENT_USER)
        .open_subkey_with_flags(key, KEY_READ)
        .and_then(|k| k.get_value::<String, _>(name))
        .is_ok()
}

fn set_enabled_in(key: &str, name: &str, enabled: bool, command: &str) -> Res<()> {
    let (run, _) = RegKey::predef(HKEY_CURRENT_USER)
        .create_subkey_with_flags(key, KEY_READ | KEY_WRITE)
        .map_err(s)?;
    if enabled {
        run.set_value(name, &command).map_err(s)
    } else {
        match run.delete_value(name) {
            Ok(()) => Ok(()),
            // Zaten yoksa istenen duruma ulaşılmıştır.
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(s(e)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;

    #[test]
    fn komut_yolu_tirnaklar() {
        assert_eq!(
            command_for(Path::new(r"C:\Users\Ali Veli\AppData\Local\sticky-reminder\sticky-reminder.exe")),
            r#""C:\Users\Ali Veli\AppData\Local\sticky-reminder\sticky-reminder.exe""#
        );
    }

    #[test]
    fn ac_kapat_gercek_kayit_defterinde_ayri_anahtarla() {
        // Gerçek Run anahtarına dokunmamak için ayrı, geçici bir HKCU anahtarı kullanılır.
        let key = r"Software\StickyReminderTest\RunTest";
        let name = "SinamaDegeri";
        assert!(!is_enabled_in(key, name));

        set_enabled_in(key, name, true, r#""C:\x y\a.exe""#).unwrap();
        assert!(is_enabled_in(key, name));
        let stored: String = RegKey::predef(HKEY_CURRENT_USER)
            .open_subkey(key)
            .unwrap()
            .get_value(name)
            .unwrap();
        assert_eq!(stored, r#""C:\x y\a.exe""#);

        set_enabled_in(key, name, false, "").unwrap();
        assert!(!is_enabled_in(key, name));
        // İkinci kapatma hata vermez.
        set_enabled_in(key, name, false, "").unwrap();

        let _ = RegKey::predef(HKEY_CURRENT_USER).delete_subkey_all(r"Software\StickyReminderTest");
    }
}

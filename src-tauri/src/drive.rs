//! Drive v3 REST çağrıları: yalnızca uygulamanın gizli klasöründeki (appDataFolder) tek dosya.

use serde_json::Value;
use uuid::Uuid;

use crate::http;
use crate::oauth::urlencode;
use crate::store::Res;
use crate::syncer::{Remote, RemoteFile};

const FILE_NAME: &str = "sticky.json";
const FILES_URL: &str = "https://www.googleapis.com/drive/v3/files";
const UPLOAD_URL: &str = "https://www.googleapis.com/upload/drive/v3/files";

pub struct DriveRemote {
    agent: ureq::Agent,
    token: String,
}

impl DriveRemote {
    pub fn new(agent: ureq::Agent, access_token: String) -> Self {
        Self { agent, token: access_token }
    }

    fn bearer(&self) -> String {
        format!("Bearer {}", self.token)
    }

    fn get(&self, url: &str) -> Res<(u16, String)> {
        http::finish(self.agent.get(url).header("Authorization", self.bearer()).call())
    }

    /// Dosyayı bulur: (id, sürüm). Birden fazla varsa (iki cihaz ilk senkronu aynı anda
    /// yaptıysa) en eski oluşturulan kullanılır; böylece iki cihaz aynı dosyada buluşur.
    fn find(&self) -> Res<Option<(String, String)>> {
        let query = format!("name = '{FILE_NAME}' and trashed = false");
        let url = format!(
            "{FILES_URL}?spaces=appDataFolder&orderBy=createdTime&pageSize=10&fields={}&q={}",
            urlencode("files(id,version)"),
            urlencode(&query)
        );
        let (status, body) = self.get(&url)?;
        if status != 200 {
            return Err(api_error(status, &body));
        }
        let json: Value = serde_json::from_str(&body).map_err(|e| format!("Drive yanıtı okunamadı: {e}"))?;
        let first = json["files"].as_array().and_then(|files| files.first());
        match first {
            None => Ok(None),
            Some(file) => Ok(Some((
                file["id"].as_str().ok_or("Drive yanıtında id yok")?.to_string(),
                file["version"].as_str().ok_or("Drive yanıtında sürüm yok")?.to_string(),
            ))),
        }
    }
}

fn api_error(status: u16, body: &str) -> String {
    match status {
        401 => "Drive yetkisi reddedildi (401); yeniden giriş gerekebilir".to_string(),
        403 => format!("Drive erişimi reddedildi (403): {}", http::shorten(body)),
        _ => format!("Drive {status}: {}", http::shorten(body)),
    }
}

fn version_of(body: &str) -> Res<String> {
    let json: Value = serde_json::from_str(body).map_err(|e| format!("Drive yanıtı okunamadı: {e}"))?;
    json["version"]
        .as_str()
        .map(str::to_string)
        .ok_or_else(|| "Drive yanıtında sürüm yok".to_string())
}

/// Dosyayı oluşturmak için `multipart/related` gövdesi: üst veri + içerik.
pub fn multipart_body(boundary: &str, content: &str) -> String {
    let metadata = format!("{{\"name\":\"{FILE_NAME}\",\"parents\":[\"appDataFolder\"]}}");
    format!(
        "--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{metadata}\r\n\
         --{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{content}\r\n--{boundary}--"
    )
}

impl Remote for DriveRemote {
    fn fetch(&mut self) -> Res<Option<RemoteFile>> {
        let Some((id, version)) = self.find()? else {
            return Ok(None);
        };
        let (status, body) = self.get(&format!("{FILES_URL}/{id}?alt=media"))?;
        if status != 200 {
            return Err(api_error(status, &body));
        }
        Ok(Some(RemoteFile { id, version, content: body }))
    }

    fn current_version(&mut self, known_id: Option<&str>) -> Res<Option<String>> {
        match known_id {
            Some(id) => {
                let (status, body) = self.get(&format!("{FILES_URL}/{id}?fields=version"))?;
                match status {
                    200 => Ok(Some(version_of(&body)?)),
                    404 => Ok(None),
                    _ => Err(api_error(status, &body)),
                }
            }
            None => Ok(self.find()?.map(|(_, version)| version)),
        }
    }

    fn upload(&mut self, existing_id: Option<&str>, content: &str) -> Res<String> {
        let result = match existing_id {
            Some(id) => self
                .agent
                .patch(format!("{UPLOAD_URL}/{id}?uploadType=media&fields=id,version"))
                .header("Authorization", self.bearer())
                .header("Content-Type", "application/json; charset=UTF-8")
                .send(content),
            None => {
                let boundary = format!("sticky-{}", Uuid::new_v4().simple());
                self.agent
                    .post(format!("{UPLOAD_URL}?uploadType=multipart&fields=id,version"))
                    .header("Authorization", self.bearer())
                    .header("Content-Type", format!("multipart/related; boundary={boundary}"))
                    .send(multipart_body(&boundary, content))
            }
        };
        let (status, body) = http::finish(result)?;
        if status != 200 {
            return Err(api_error(status, &body));
        }
        version_of(&body)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn multipart_govdesi_bicimi_dogru() {
        let body = multipart_body("B1", "{\"version\":1,\"tasks\":[]}");
        assert!(body.starts_with("--B1\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{\"name\":\"sticky.json\""));
        assert!(body.contains("\"parents\":[\"appDataFolder\"]"));
        assert!(body.contains("\r\n--B1\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{\"version\":1,\"tasks\":[]}\r\n--B1--"));
        assert!(body.ends_with("--B1--"));
    }

    #[test]
    fn surum_ayristirma() {
        assert_eq!(version_of("{\"id\":\"x\",\"version\":\"42\"}").unwrap(), "42");
        assert!(version_of("{\"id\":\"x\"}").is_err());
        assert!(version_of("değil").is_err());
    }

    #[test]
    fn api_hata_iletileri() {
        assert!(api_error(401, "").contains("yeniden giriş"));
        assert!(api_error(403, "{\"error\":\"rateLimit\"}").contains("403"));
        assert!(api_error(500, "boom").contains("500"));
    }
}

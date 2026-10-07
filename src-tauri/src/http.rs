use crate::store::Res;

/// Yanıtı (durum kodu, gövde) çiftine çevirir. `ureq` ayarında 4xx/5xx hata sayılmadığı için
/// durum kodunu çağıran karar verir.
pub fn finish(
    result: Result<ureq::http::Response<ureq::Body>, ureq::Error>,
) -> Res<(u16, String)> {
    let mut response = result.map_err(|e| format!("Ağ hatası: {e}"))?;
    let status = response.status().as_u16();
    let body = response
        .body_mut()
        .read_to_string()
        .map_err(|e| format!("Ağ hatası: {e}"))?;
    Ok((status, body))
}

/// Hata iletisinde göstermek için gövdenin ilk kısmı.
pub fn shorten(body: &str) -> String {
    let flat: String = body.split_whitespace().collect::<Vec<_>>().join(" ");
    flat.chars().take(200).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shorten_bosluklari_siler_ve_keser() {
        assert_eq!(shorten("a\n  b\t c"), "a b c");
        assert_eq!(shorten(&"x".repeat(500)).chars().count(), 200);
    }
}

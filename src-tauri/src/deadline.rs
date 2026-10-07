const MAX_YEAR: i64 = 9999;

/// "GG.AA.YYYY" veya "GG.AA.YYYY SS:DD" metnini, yazılan zamanı saat dilimsiz
/// sayarak 1970'ten beri geçen milisaniyeye çevirir. Saat yazılmazsa günün sonu
/// (23:59) alınır. Okunamayan metin için `None` döner.
pub fn parse_deadline(text: &str) -> Option<i64> {
    let text = text.trim();
    let (date_part, time_part) = match text.split_once(char::is_whitespace) {
        Some((d, t)) => (d, Some(t.trim())),
        None => (text, None),
    };

    let mut parts = date_part.split('.');
    let day = number(parts.next()?)?;
    let month = number(parts.next()?)?;
    let year = number(parts.next()?)?;
    if parts.next().is_some() {
        return None;
    }

    let (hour, minute) = match time_part {
        None => (23, 59),
        Some(t) => {
            let (h, m) = t.split_once(':')?;
            (number(h)?, number(m)?)
        }
    };

    if !(1970..=MAX_YEAR).contains(&year)
        || !(1..=12).contains(&month)
        || day < 1
        || day > days_in_month(year, month)
        || hour > 23
        || minute > 59
    {
        return None;
    }

    let days = days_from_civil(year, month, day);
    Some(((days * 24 + hour) * 60 + minute) * 60_000)
}

fn number(s: &str) -> Option<i64> {
    if s.is_empty() || s.len() > 4 || !s.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    s.parse().ok()
}

fn is_leap(year: i64) -> bool {
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
}

fn days_in_month(year: i64, month: i64) -> i64 {
    match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        _ if is_leap(year) => 29,
        _ => 28,
    }
}

/// 1970-01-01'den itibaren gün sayısı (Howard Hinnant'ın algoritması).
fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let y = if month <= 2 { year - 1 } else { year };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (month + 9) % 12; // mart = 0
    let doy = (153 * mp + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

#[cfg(test)]
mod tests {
    use super::*;

    const DAY: i64 = 86_400_000;

    #[test]
    fn epoch_basi_sifirdir() {
        assert_eq!(parse_deadline("01.01.1970 00:00"), Some(0));
        assert_eq!(parse_deadline("02.01.1970 00:00"), Some(DAY));
    }

    #[test]
    fn saat_yoksa_gun_sonu() {
        assert_eq!(parse_deadline("25.12.2026"), parse_deadline("25.12.2026 23:59"));
    }

    #[test]
    fn artik_yil() {
        let a = parse_deadline("28.02.2024 00:00").unwrap();
        let b = parse_deadline("01.03.2024 00:00").unwrap();
        assert_eq!(b - a, 2 * DAY);
        let c = parse_deadline("28.02.2025 00:00").unwrap();
        let d = parse_deadline("01.03.2025 00:00").unwrap();
        assert_eq!(d - c, DAY);
    }

    #[test]
    fn tek_haneli_ve_bosluklar() {
        assert_eq!(parse_deadline("  5.1.2026   9:05 "), parse_deadline("05.01.2026 09:05"));
    }

    #[test]
    fn gecersizler_none_doner() {
        for s in [
            "", "abc", "31.04.2026", "29.02.2025", "1.1.2026 25:00", "1.1.2026 10:60",
            "1.13.2026", "0.1.2026", "1.1.1969", "1.1.2026.5", "1.1.2026 10", "yarın",
            "1.1.2026 10:00 fazla",
        ] {
            assert_eq!(parse_deadline(s), None, "{s:?}");
        }
    }
}

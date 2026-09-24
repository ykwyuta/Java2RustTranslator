//! java.lang.Character の static メソッド（UTF-16 コード単位 `u16` を受け取る）。

fn to_char(c: u16) -> Option<char> {
    char::from_u32(c as u32)
}

/// `Character.isDigit(char)`。
pub fn is_digit(c: u16) -> bool {
    to_char(c).is_some_and(|ch| ch.is_ascii_digit() || (!ch.is_ascii() && ch.is_numeric()))
}

/// `Character.isLetter(char)`。
pub fn is_letter(c: u16) -> bool {
    to_char(c).is_some_and(char::is_alphabetic)
}

/// `Character.isLetterOrDigit(char)`。
pub fn is_letter_or_digit(c: u16) -> bool {
    is_letter(c) || is_digit(c)
}

/// `Character.isAlphabetic(int)`。
pub fn is_alphabetic(c: i32) -> bool {
    u32::try_from(c).ok().and_then(char::from_u32).is_some_and(char::is_alphabetic)
}

/// `Character.isWhitespace(char)`: Unicode の空白（ノーブレークスペースを除く）と制御文字 \t \n \u000B \f \r \u001C-\u001F。
pub fn is_whitespace(c: u16) -> bool {
    matches!(c, 0x09..=0x0D | 0x1C..=0x1F)
        || (c != 0x00A0 && c != 0x2007 && c != 0x202F && to_char(c).is_some_and(char::is_whitespace))
}

pub fn is_upper_case(c: u16) -> bool {
    to_char(c).is_some_and(char::is_uppercase)
}

pub fn is_lower_case(c: u16) -> bool {
    to_char(c).is_some_and(char::is_lowercase)
}

/// `Character.toUpperCase(char)`（1 文字に写る場合のみ変換）。
pub fn to_upper_case(c: u16) -> u16 {
    map_single(c, |ch| ch.to_uppercase().collect())
}

/// `Character.toLowerCase(char)`。
pub fn to_lower_case(c: u16) -> u16 {
    map_single(c, |ch| ch.to_lowercase().collect())
}

fn map_single(c: u16, f: impl Fn(char) -> Vec<char>) -> u16 {
    match to_char(c) {
        Some(ch) => {
            let mapped = f(ch);
            if mapped.len() == 1 && (mapped[0] as u32) <= 0xFFFF {
                mapped[0] as u32 as u16
            } else {
                c
            }
        }
        None => c,
    }
}

/// `Character.getNumericValue(char)`（ASCII の 0-9, a-z, A-Z のみ）。
pub fn get_numeric_value(c: u16) -> i32 {
    match to_char(c) {
        Some(ch) if ch.is_ascii_digit() => ch as i32 - '0' as i32,
        Some(ch) if ch.is_ascii_alphabetic() => ch.to_ascii_lowercase() as i32 - 'a' as i32 + 10,
        _ => -1,
    }
}

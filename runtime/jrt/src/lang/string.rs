//! java.lang.String 相当の不変文字列。

use crate::array::JArray;
use crate::lang::stringify::JStringify;
use crate::rt::throw;
use std::cmp::Ordering;
use std::fmt;
use std::hash::{Hash, Hasher};
use std::rc::Rc;

/// Java の `String`。
///
/// 中身は UTF-8 で保持し、`length()` / `charAt()` などは Java と同じ UTF-16 単位で振る舞う。
/// ASCII のみの文字列（`ascii == true`）では添字アクセスが O(1)。
#[derive(Clone)]
pub struct JString {
    repr: Repr,
    ascii: bool,
}

#[derive(Clone)]
enum Repr {
    Static(&'static str),
    Heap(Rc<str>),
}

const fn is_ascii_const(s: &str) -> bool {
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] >= 0x80 {
            return false;
        }
        i += 1;
    }
    true
}

impl JString {
    /// 文字列リテラルから作る（`jstr!` マクロの展開先）。
    pub const fn from_static(s: &'static str) -> JString {
        JString { repr: Repr::Static(s), ascii: is_ascii_const(s) }
    }

    pub fn as_str(&self) -> &str {
        match &self.repr {
            Repr::Static(s) => s,
            Repr::Heap(s) => s,
        }
    }

    fn from_utf16(units: &[u16]) -> JString {
        JString::from(String::from_utf16_lossy(units))
    }

    fn utf16(&self) -> Vec<u16> {
        self.as_str().encode_utf16().collect()
    }

    fn check_index(&self, index: i32, length: i32) {
        if index < 0 || index >= length {
            throw(
                "java.lang.StringIndexOutOfBoundsException",
                Some(&format!("Index {index} out of bounds for length {length}")),
            );
        }
    }

    fn check_range(&self, begin: i32, end: i32, length: i32) {
        if begin < 0 || begin > end || end > length {
            throw(
                "java.lang.StringIndexOutOfBoundsException",
                Some(&format!("begin {begin}, end {end}, length {length}")),
            );
        }
    }

    /// `length()`（UTF-16 単位）。
    pub fn length(&self) -> i32 {
        if self.ascii {
            self.as_str().len() as i32
        } else {
            self.as_str().encode_utf16().count() as i32
        }
    }

    pub fn is_empty(&self) -> bool {
        self.as_str().is_empty()
    }

    /// `isBlank()`。
    pub fn is_blank(&self) -> bool {
        self.as_str().chars().all(char::is_whitespace)
    }

    /// `charAt(index)`。
    pub fn char_at(&self, index: i32) -> u16 {
        let len = self.length();
        self.check_index(index, len);
        if self.ascii {
            self.as_str().as_bytes()[index as usize] as u16
        } else {
            self.as_str().encode_utf16().nth(index as usize).unwrap_or(0)
        }
    }

    /// `equals(Object)`（引数が String の場合）。
    pub fn equals(&self, other: &JString) -> bool {
        self.as_str() == other.as_str()
    }

    pub fn equals_ignore_case(&self, other: &JString) -> bool {
        self.length() == other.length()
            && self.as_str().chars().zip(other.as_str().chars()).all(|(a, b)| {
                a == b || a.to_uppercase().eq(b.to_uppercase()) || a.to_lowercase().eq(b.to_lowercase())
            })
    }

    /// `compareTo(String)`: UTF-16 単位の辞書順。差は最初に異なる文字の差、または長さの差。
    pub fn compare_to(&self, other: &JString) -> i32 {
        let mut a = self.as_str().encode_utf16();
        let mut b = other.as_str().encode_utf16();
        loop {
            match (a.next(), b.next()) {
                (Some(x), Some(y)) if x != y => return x as i32 - y as i32,
                (Some(_), Some(_)) => {}
                _ => return self.length() - other.length(),
            }
        }
    }

    /// `hashCode()`: s[0]*31^(n-1) + ... + s[n-1]（int のラップアラウンド）。
    pub fn hash_code(&self) -> i32 {
        self.as_str().encode_utf16().fold(0i32, |h, c| h.wrapping_mul(31).wrapping_add(c as i32))
    }

    /// `substring(begin)`。
    pub fn substring(&self, begin: i32) -> JString {
        self.substring_range(begin, self.length())
    }

    /// `substring(begin, end)`。
    pub fn substring_range(&self, begin: i32, end: i32) -> JString {
        let len = self.length();
        self.check_range(begin, end, len);
        if self.ascii {
            JString::from(self.as_str()[begin as usize..end as usize].to_string())
        } else {
            JString::from_utf16(&self.utf16()[begin as usize..end as usize])
        }
    }

    /// `indexOf(String)`。
    pub fn index_of(&self, needle: &JString) -> i32 {
        self.index_of_from(needle, 0)
    }

    /// `indexOf(String, fromIndex)`。
    pub fn index_of_from(&self, needle: &JString, from: i32) -> i32 {
        let hay = self.utf16();
        let n = needle.utf16();
        let start = from.max(0) as usize;
        if n.is_empty() {
            return (start.min(hay.len())) as i32;
        }
        if n.len() > hay.len() {
            return -1;
        }
        (start..=hay.len() - n.len()).find(|&i| hay[i..i + n.len()] == n[..]).map_or(-1, |i| i as i32)
    }

    /// `lastIndexOf(String)`。
    pub fn last_index_of(&self, needle: &JString) -> i32 {
        let hay = self.utf16();
        let n = needle.utf16();
        if n.len() > hay.len() {
            return -1;
        }
        (0..=hay.len() - n.len()).rev().find(|&i| hay[i..i + n.len()] == n[..]).map_or(-1, |i| i as i32)
    }

    /// `indexOf(int ch)`。
    pub fn index_of_char(&self, ch: i32) -> i32 {
        self.as_str().encode_utf16().position(|c| c as i32 == ch).map_or(-1, |i| i as i32)
    }

    /// `lastIndexOf(int ch)`。
    pub fn last_index_of_char(&self, ch: i32) -> i32 {
        let v = self.utf16();
        v.iter().rposition(|&c| c as i32 == ch).map_or(-1, |i| i as i32)
    }

    pub fn contains(&self, s: &JString) -> bool {
        self.as_str().contains(s.as_str())
    }

    pub fn starts_with(&self, s: &JString) -> bool {
        self.as_str().starts_with(s.as_str())
    }

    pub fn ends_with(&self, s: &JString) -> bool {
        self.as_str().ends_with(s.as_str())
    }

    pub fn to_upper_case(&self) -> JString {
        JString::from(self.as_str().to_uppercase())
    }

    pub fn to_lower_case(&self) -> JString {
        JString::from(self.as_str().to_lowercase())
    }

    /// `trim()`: 先頭・末尾の U+0020 以下の文字を除く。
    pub fn trim(&self) -> JString {
        JString::from(self.as_str().trim_matches(|c: char| c <= ' ').to_string())
    }

    /// `strip()`: Unicode の空白を除く。
    pub fn strip(&self) -> JString {
        JString::from(self.as_str().trim().to_string())
    }

    pub fn concat(&self, other: &JString) -> JString {
        JString::from(format!("{}{}", self.as_str(), other.as_str()))
    }

    /// `replace(char, char)`。
    pub fn replace_char(&self, from: u16, to: u16) -> JString {
        let v: Vec<u16> = self.as_str().encode_utf16().map(|c| if c == from { to } else { c }).collect();
        JString::from_utf16(&v)
    }

    /// `replace(CharSequence, CharSequence)`。
    pub fn replace(&self, from: &JString, to: &JString) -> JString {
        if from.is_empty() {
            // Java: 各文字の間と前後に挿入する。
            let mut out = String::from(to.as_str());
            for c in self.as_str().chars() {
                out.push(c);
                out.push_str(to.as_str());
            }
            return JString::from(out);
        }
        JString::from(self.as_str().replace(from.as_str(), to.as_str()))
    }

    /// `repeat(count)`。
    pub fn repeat(&self, count: i32) -> JString {
        if count < 0 {
            throw("java.lang.IllegalArgumentException", Some(&format!("count is negative: {count}")));
        }
        JString::from(self.as_str().repeat(count as usize))
    }

    /// `toCharArray()`。
    pub fn to_char_array(&self) -> JArray<u16> {
        JArray::from_vec(self.utf16())
    }

    /// `String.valueOf(x)` / `Integer.toString(x)` など。
    pub fn value_of<T: JStringify + ?Sized>(v: &T) -> JString {
        let mut s = String::new();
        v.append_to(&mut s);
        JString::from(s)
    }

    /// `String.valueOf(char[])` / `new String(char[])`。
    pub fn from_chars(chars: &JArray<u16>) -> JString {
        JString::from_utf16(&chars.to_vec())
    }
}

impl From<String> for JString {
    fn from(s: String) -> JString {
        let ascii = s.is_ascii();
        JString { repr: Repr::Heap(Rc::from(s)), ascii }
    }
}

impl From<&str> for JString {
    fn from(s: &str) -> JString {
        JString::from(s.to_string())
    }
}

/// Java の `null` を表せないため、既定値は空文字列とする（変換時に J2R-LOSSY-NULL 診断が出る）。
impl Default for JString {
    fn default() -> JString {
        JString::from_static("")
    }
}

impl PartialEq for JString {
    fn eq(&self, other: &JString) -> bool {
        self.as_str() == other.as_str()
    }
}

impl Eq for JString {}

impl Hash for JString {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.as_str().hash(state)
    }
}

impl PartialOrd for JString {
    fn partial_cmp(&self, other: &JString) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

/// `compareTo` と同じ順序（UTF-16 単位の辞書順）。
impl Ord for JString {
    fn cmp(&self, other: &JString) -> Ordering {
        self.as_str().encode_utf16().cmp(other.as_str().encode_utf16())
    }
}

impl fmt::Display for JString {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl fmt::Debug for JString {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Debug::fmt(self.as_str(), f)
    }
}

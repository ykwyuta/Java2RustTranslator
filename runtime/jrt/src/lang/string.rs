//! java.lang.String 相当の不変文字列。

use crate::array::JArray;
use crate::lang::stringify::JStringify;
use crate::rt::{npe, throw, JResult};
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
    Null,
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

    /// Java の `null`。
    pub const fn null() -> JString {
        JString { repr: Repr::Null, ascii: false }
    }

    pub fn is_null(&self) -> bool {
        matches!(self.repr, Repr::Null)
    }

    /// 中身。null なら NullPointerException。
    pub fn as_str(&self) -> JResult<&str> {
        match &self.repr {
            Repr::Null => npe(),
            Repr::Static(s) => Ok(s),
            Repr::Heap(s) => Ok(s),
        }
    }

    /// 中身（null なら "null"。表示用）。
    pub fn as_str_or_null(&self) -> &str {
        self.opt_str().unwrap_or("null")
    }

    /// 中身（null なら None）。
    pub fn opt_str(&self) -> Option<&str> {
        match &self.repr {
            Repr::Null => None,
            Repr::Static(s) => Some(s),
            Repr::Heap(s) => Some(s),
        }
    }

    fn from_utf16(units: &[u16]) -> JString {
        JString::from(String::from_utf16_lossy(units))
    }

    fn utf16(&self) -> JResult<Vec<u16>> {
        Ok(self.as_str()?.encode_utf16().collect())
    }

    fn check_index(index: i32, length: i32) -> JResult<()> {
        if index < 0 || index >= length {
            return throw(
                "java.lang.StringIndexOutOfBoundsException",
                Some(&format!("Index {index} out of bounds for length {length}")),
            );
        }
        Ok(())
    }

    fn check_range(begin: i32, end: i32, length: i32) -> JResult<()> {
        if begin < 0 || begin > end || end > length {
            return throw(
                "java.lang.StringIndexOutOfBoundsException",
                Some(&format!("begin {begin}, end {end}, length {length}")),
            );
        }
        Ok(())
    }

    /// `length()`（UTF-16 単位）。
    pub fn length(&self) -> JResult<i32> {
        let s = self.as_str()?;
        Ok(if self.ascii { s.len() as i32 } else { s.encode_utf16().count() as i32 })
    }

    pub fn is_empty(&self) -> JResult<bool> {
        Ok(self.as_str()?.is_empty())
    }

    /// `isBlank()`。
    pub fn is_blank(&self) -> JResult<bool> {
        Ok(self.as_str()?.chars().all(char::is_whitespace))
    }

    /// `charAt(index)`。
    pub fn char_at(&self, index: i32) -> JResult<u16> {
        let len = self.length()?;
        JString::check_index(index, len)?;
        let s = self.as_str()?;
        Ok(if self.ascii { s.as_bytes()[index as usize] as u16 } else { s.encode_utf16().nth(index as usize).unwrap_or(0) })
    }

    /// `equals(Object)`。引数は String でも Object でもよい（String 以外なら false）。
    pub fn equals<T: EqArg + ?Sized>(&self, other: &T) -> JResult<bool> {
        let me = self.as_str()?;
        Ok(other.string_value().is_some_and(|o| o.opt_str() == Some(me)))
    }

    pub fn equals_ignore_case(&self, other: &JString) -> JResult<bool> {
        let me = self.as_str()?;
        let Some(o) = other.opt_str() else { return Ok(false) };
        Ok(self.length()? == other.length()?
            && me.chars().zip(o.chars()).all(|(a, b)| {
                a == b || a.to_uppercase().eq(b.to_uppercase()) || a.to_lowercase().eq(b.to_lowercase())
            }))
    }

    /// `compareTo(String)`: UTF-16 単位の辞書順。差は最初に異なる文字の差、または長さの差。
    pub fn compare_to(&self, other: &JString) -> JResult<i32> {
        let mut a = self.as_str()?.encode_utf16();
        let mut b = other.as_str()?.encode_utf16();
        loop {
            match (a.next(), b.next()) {
                (Some(x), Some(y)) if x != y => return Ok(x as i32 - y as i32),
                (Some(_), Some(_)) => {}
                _ => return Ok(self.length()? - other.length()?),
            }
        }
    }

    /// `hashCode()`: s[0]*31^(n-1) + ... + s[n-1]（int のラップアラウンド）。
    pub fn hash_code(&self) -> JResult<i32> {
        Ok(self.as_str()?.encode_utf16().fold(0i32, |h, c| h.wrapping_mul(31).wrapping_add(c as i32)))
    }

    /// `substring(begin)`。
    pub fn substring(&self, begin: i32) -> JResult<JString> {
        self.substring_range(begin, self.length()?)
    }

    /// `substring(begin, end)`。
    pub fn substring_range(&self, begin: i32, end: i32) -> JResult<JString> {
        let len = self.length()?;
        JString::check_range(begin, end, len)?;
        if self.ascii {
            Ok(JString::from(self.as_str()?[begin as usize..end as usize].to_string()))
        } else {
            Ok(JString::from_utf16(&self.utf16()?[begin as usize..end as usize]))
        }
    }

    /// `indexOf(String)`。
    pub fn index_of(&self, needle: &JString) -> JResult<i32> {
        self.index_of_from(needle, 0)
    }

    /// `indexOf(String, fromIndex)`。
    pub fn index_of_from(&self, needle: &JString, from: i32) -> JResult<i32> {
        let hay = self.utf16()?;
        let n = needle.utf16()?;
        let start = from.max(0) as usize;
        if n.is_empty() {
            return Ok((start.min(hay.len())) as i32);
        }
        if n.len() > hay.len() {
            return Ok(-1);
        }
        Ok((start..=hay.len() - n.len()).find(|&i| hay[i..i + n.len()] == n[..]).map_or(-1, |i| i as i32))
    }

    /// `lastIndexOf(String)`。
    pub fn last_index_of(&self, needle: &JString) -> JResult<i32> {
        let hay = self.utf16()?;
        let n = needle.utf16()?;
        if n.len() > hay.len() {
            return Ok(-1);
        }
        Ok((0..=hay.len() - n.len()).rev().find(|&i| hay[i..i + n.len()] == n[..]).map_or(-1, |i| i as i32))
    }

    /// `indexOf(int ch)`。
    pub fn index_of_char(&self, ch: i32) -> JResult<i32> {
        Ok(self.as_str()?.encode_utf16().position(|c| c as i32 == ch).map_or(-1, |i| i as i32))
    }

    /// `lastIndexOf(int ch)`。
    pub fn last_index_of_char(&self, ch: i32) -> JResult<i32> {
        let v = self.utf16()?;
        Ok(v.iter().rposition(|&c| c as i32 == ch).map_or(-1, |i| i as i32))
    }

    pub fn contains(&self, s: &JString) -> JResult<bool> {
        Ok(self.as_str()?.contains(s.as_str()?))
    }

    pub fn starts_with(&self, s: &JString) -> JResult<bool> {
        Ok(self.as_str()?.starts_with(s.as_str()?))
    }

    pub fn ends_with(&self, s: &JString) -> JResult<bool> {
        Ok(self.as_str()?.ends_with(s.as_str()?))
    }

    pub fn to_upper_case(&self) -> JResult<JString> {
        Ok(JString::from(self.as_str()?.to_uppercase()))
    }

    pub fn to_lower_case(&self) -> JResult<JString> {
        Ok(JString::from(self.as_str()?.to_lowercase()))
    }

    /// `trim()`: 先頭・末尾の U+0020 以下の文字を除く。
    pub fn trim(&self) -> JResult<JString> {
        Ok(JString::from(self.as_str()?.trim_matches(|c: char| c <= ' ').to_string()))
    }

    /// `strip()`: Unicode の空白を除く。
    pub fn strip(&self) -> JResult<JString> {
        Ok(JString::from(self.as_str()?.trim().to_string()))
    }

    pub fn concat(&self, other: &JString) -> JResult<JString> {
        Ok(JString::from(format!("{}{}", self.as_str()?, other.as_str()?)))
    }

    /// `replace(char, char)`。
    pub fn replace_char(&self, from: u16, to: u16) -> JResult<JString> {
        let v: Vec<u16> = self.as_str()?.encode_utf16().map(|c| if c == from { to } else { c }).collect();
        Ok(JString::from_utf16(&v))
    }

    /// `replace(CharSequence, CharSequence)`。
    pub fn replace(&self, from: &JString, to: &JString) -> JResult<JString> {
        let (me, from, to) = (self.as_str()?, from.as_str()?, to.as_str()?);
        if from.is_empty() {
            // Java: 各文字の間と前後に挿入する。
            let mut out = String::from(to);
            for c in me.chars() {
                out.push(c);
                out.push_str(to);
            }
            return Ok(JString::from(out));
        }
        Ok(JString::from(me.replace(from, to)))
    }

    /// `repeat(count)`。
    pub fn repeat(&self, count: i32) -> JResult<JString> {
        let me = self.as_str()?;
        if count < 0 {
            return throw("java.lang.IllegalArgumentException", Some(&format!("count is negative: {count}")));
        }
        Ok(JString::from(me.repeat(count as usize)))
    }

    /// `toCharArray()`。
    pub fn to_char_array(&self) -> JResult<JArray<u16>> {
        Ok(JArray::from_vec(self.utf16()?))
    }

    /// `String.valueOf(x)` / `Integer.toString(x)` など（プリミティブ型・String・char）。
    pub fn value_of<T: JStringify + ?Sized>(v: &T) -> JString {
        let mut s = String::new();
        v.append_to(&mut s);
        JString::from(s)
    }

    /// `String.valueOf(char[])` / `new String(char[])`。
    pub fn from_chars(chars: &JArray<u16>) -> JResult<JString> {
        Ok(JString::from_utf16(&chars.to_vec()?))
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

/// 参照型の既定値は Java と同じく null。
impl Default for JString {
    fn default() -> JString {
        JString::null()
    }
}

/// 値の比較（null どうしは等しい）。
impl PartialEq for JString {
    fn eq(&self, other: &JString) -> bool {
        self.opt_str() == other.opt_str()
    }
}

impl Eq for JString {}

impl Hash for JString {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.opt_str().hash(state)
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
        // null は最小として扱う（Java の比較は NullPointerException になるので、生成コードからは使わない）。
        match (self.opt_str(), other.opt_str()) {
            (Some(a), Some(b)) => a.encode_utf16().cmp(b.encode_utf16()),
            (a, b) => a.is_some().cmp(&b.is_some()),
        }
    }
}

impl fmt::Display for JString {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.opt_str().unwrap_or("null"))
    }
}

impl fmt::Debug for JString {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self.opt_str() {
            Some(s) => fmt::Debug::fmt(s, f),
            None => f.write_str("null"),
        }
    }
}

/// `String.equals(Object)` の引数になれる型。
pub trait EqArg {
    /// 値が String なら Some。
    fn string_value(&self) -> Option<JString>;
}

impl EqArg for JString {
    fn string_value(&self) -> Option<JString> {
        if self.is_null() {
            None
        } else {
            Some(self.clone())
        }
    }
}

impl EqArg for crate::object::JObject {
    fn string_value(&self) -> Option<JString> {
        if self.instance_of("java.lang.String") {
            self.cast_string().ok()
        } else {
            None
        }
    }
}

impl JString {
    /// `String.join(sep, elements)`（elements は Iterable）。
    pub fn join(sep: &JString, elements: &crate::object::JObject) -> JResult<JString> {
        let sep = sep.as_str()?;
        let mut parts = Vec::new();
        for x in crate::util::collections::to_vec(elements)? {
            parts.push(crate::lang::stringify::to_java_string(&x)?);
        }
        Ok(JString::from(parts.join(sep)))
    }

    /// `String.join(sep, a, b, ...)`。
    pub fn join_array<T: Clone + crate::JToString>(sep: &JString, elements: &JArray<T>) -> JResult<JString> {
        let sep = sep.as_str()?;
        let mut parts = Vec::new();
        for x in elements.to_vec()? {
            parts.push(crate::lang::stringify::to_java_string(&x)?);
        }
        Ok(JString::from(parts.join(sep)))
    }

    /// `split(regex)`（Java と同じく末尾の空文字列を除く）。
    pub fn split(&self, regex: &JString) -> JResult<JArray<JString>> {
        crate::lang::regex::split(self, regex, 0)
    }

    /// `String.valueOf(Object)`（null は "null"）。
    pub fn value_of_object(o: &crate::object::JObject) -> JResult<JString> {
        if o.is_null() {
            Ok(JString::from("null"))
        } else {
            o.to_jstring()
        }
    }

    /// `compareToIgnoreCase(String)`。
    pub fn compare_to_ignore_case(&self, other: &JString) -> JResult<i32> {
        self.to_lower_case()?.compare_to(&other.to_lower_case()?)
    }
}

/// `CharSequence` として渡される値（String・StringBuilder・Object）。
pub trait CharSeq {
    fn char_seq(&self) -> JResult<JString>;
}

impl CharSeq for JString {
    fn char_seq(&self) -> JResult<JString> {
        self.as_str()?;
        Ok(self.clone())
    }
}

impl CharSeq for crate::object::JObject {
    fn char_seq(&self) -> JResult<JString> {
        self.to_jstring()
    }
}

impl CharSeq for crate::lang::StringBuilder {
    fn char_seq(&self) -> JResult<JString> {
        Ok(self.to_jstring())
    }
}

/// `CharSequence` の引数を String にする（null なら NullPointerException）。
pub fn char_sequence<T: CharSeq + ?Sized>(x: &T) -> JResult<JString> {
    x.char_seq()
}

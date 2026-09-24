//! `String.format` / `printf`（java.util.Formatter のよく使う部分）。
//!
//! 対応する変換: `%d %s %S %f %e %E %g %x %X %o %c %b %B %h %n %%`、
//! フラグ `- 0 + , ( #` と空白、幅、精度、引数番号 `%1$s`。
//! `%f` / `%e` の丸めは Java と同じく、最短の 10 進表現を HALF_UP で丸める。

use crate::array::JArray;
use crate::lang::boxed::{JByte, JCharacter, JDouble, JFloat, JInteger, JLong, JShort};
use crate::lang::string::JString;
use crate::lang::stringify::JChar;
use crate::object::JObject;
use crate::rt::{throw, JResult};

/// `String.format(fmt, args...)`。
pub fn format(fmt: &JString, args: &JArray<JObject>) -> JResult<JString> {
    let args = if args.is_null() { Vec::new() } else { args.to_vec()? };
    Ok(JString::from(format_str(fmt.as_str()?, &args)?))
}

#[derive(Default)]
struct Spec {
    left: bool,
    zero: bool,
    plus: bool,
    space: bool,
    comma: bool,
    paren: bool,
    alt: bool,
    width: Option<usize>,
    precision: Option<usize>,
}

fn illegal<T>(msg: String) -> JResult<T> {
    throw("java.util.IllegalFormatException", Some(&msg))
}

pub fn format_str(fmt: &str, args: &[JObject]) -> JResult<String> {
    let chars: Vec<char> = fmt.chars().collect();
    let mut out = String::new();
    let mut i = 0;
    let mut next_arg = 0usize;
    while i < chars.len() {
        let c = chars[i];
        if c != '%' {
            out.push(c);
            i += 1;
            continue;
        }
        i += 1;
        // 引数番号 n$
        let mut j = i;
        while j < chars.len() && chars[j].is_ascii_digit() {
            j += 1;
        }
        let mut explicit_index = None;
        if j < chars.len() && j > i && chars[j] == '$' {
            let n: usize = chars[i..j].iter().collect::<String>().parse().unwrap_or(1);
            explicit_index = Some(n.saturating_sub(1));
            i = j + 1;
        }
        let mut spec = Spec::default();
        while i < chars.len() {
            match chars[i] {
                '-' => spec.left = true,
                '0' => spec.zero = true,
                '+' => spec.plus = true,
                ' ' => spec.space = true,
                ',' => spec.comma = true,
                '(' => spec.paren = true,
                '#' => spec.alt = true,
                _ => break,
            }
            i += 1;
        }
        let start = i;
        while i < chars.len() && chars[i].is_ascii_digit() {
            i += 1;
        }
        if i > start {
            spec.width = chars[start..i].iter().collect::<String>().parse().ok();
        }
        if i < chars.len() && chars[i] == '.' {
            i += 1;
            let ps = i;
            while i < chars.len() && chars[i].is_ascii_digit() {
                i += 1;
            }
            spec.precision = Some(chars[ps..i].iter().collect::<String>().parse().unwrap_or(0));
        }
        if i >= chars.len() {
            return illegal(format!("Format specifier '%{}'", chars[start..].iter().collect::<String>()));
        }
        let conv = chars[i];
        i += 1;
        if conv == 'n' {
            out.push('\n');
            continue;
        }
        if conv == '%' {
            out.push_str(&pad(&spec, "%".to_string()));
            continue;
        }
        let idx = match explicit_index {
            Some(n) => n,
            None => {
                next_arg += 1;
                next_arg - 1
            }
        };
        let arg = match args.get(idx) {
            Some(a) => a.clone(),
            None => return throw("java.util.MissingFormatArgumentException", Some(&format!("Format specifier '%{conv}'"))),
        };
        let text = convert(conv, &spec, &arg)?;
        out.push_str(&pad(&spec, text));
    }
    Ok(out)
}

fn pad(spec: &Spec, s: String) -> String {
    let len = s.chars().count();
    match spec.width {
        Some(w) if w > len => {
            if spec.left {
                format!("{s}{}", " ".repeat(w - len))
            } else {
                format!("{}{s}", " ".repeat(w - len))
            }
        }
        _ => s,
    }
}

fn stringify(o: &JObject) -> JResult<String> {
    crate::lang::stringify::to_java_string(o)
}

enum Num {
    Int(i64, u32),
    Float(f64),
    None,
}

fn number(o: &JObject) -> Num {
    if let Some(b) = o.downcast_ref::<JInteger>() {
        return Num::Int(b.value as i64, 32);
    }
    if let Some(b) = o.downcast_ref::<JLong>() {
        return Num::Int(b.value, 64);
    }
    if let Some(b) = o.downcast_ref::<JShort>() {
        return Num::Int(b.value as i64, 16);
    }
    if let Some(b) = o.downcast_ref::<JByte>() {
        return Num::Int(b.value as i64, 8);
    }
    if let Some(b) = o.downcast_ref::<JDouble>() {
        return Num::Float(b.value);
    }
    if let Some(b) = o.downcast_ref::<JFloat>() {
        return Num::Float(b.value as f64);
    }
    Num::None
}

fn mismatch<T>(conv: char, o: &JObject) -> JResult<T> {
    throw(
        "java.util.IllegalFormatConversionException",
        Some(&format!("{conv} != {}", o.class_name()?)),
    )
}

fn convert(conv: char, spec: &Spec, arg: &JObject) -> JResult<String> {
    Ok(match conv {
        's' | 'S' => {
            let mut s = stringify(arg)?;
            if let Some(p) = spec.precision {
                s = s.chars().take(p).collect();
            }
            if conv == 'S' {
                s = s.to_uppercase();
            }
            s
        }
        'b' | 'B' => {
            let v = if arg.is_null() {
                false
            } else if let Some(b) = arg.downcast_ref::<crate::lang::boxed::JBoolean>() {
                b.value
            } else {
                true
            };
            let s = v.to_string();
            if conv == 'B' { s.to_uppercase() } else { s }
        }
        'h' | 'H' => {
            let s = if arg.is_null() { "null".to_string() } else { format!("{:x}", arg.hash_code()? as u32) };
            if conv == 'H' { s.to_uppercase() } else { s }
        }
        'c' | 'C' => {
            if arg.is_null() {
                return Ok("null".to_string());
            }
            let s = if let Some(c) = arg.downcast_ref::<JCharacter>() {
                JString::value_of(&JChar(c.value)).to_string()
            } else if let Num::Int(v, _) = number(arg) {
                char::from_u32(v as u32).map(|c| c.to_string()).unwrap_or_default()
            } else {
                return mismatch(conv, arg);
            };
            if conv == 'C' { s.to_uppercase() } else { s }
        }
        'd' => match number(arg) {
            Num::Int(v, _) => integer(spec, v as i128),
            _ if arg.is_null() => "null".to_string(),
            _ => return mismatch(conv, arg),
        },
        'x' | 'X' | 'o' => match number(arg) {
            Num::Int(v, bits) => {
                let u = if v < 0 { (v as i128 + (1i128 << bits)) as u128 } else { v as u128 };
                let mut s = if conv == 'o' { format!("{u:o}") } else { format!("{u:x}") };
                if spec.alt {
                    s = if conv == 'o' { format!("0{s}") } else { format!("0x{s}") };
                }
                if conv == 'X' {
                    s = s.to_uppercase();
                }
                zero_pad(spec, s, false)
            }
            _ if arg.is_null() => "null".to_string(),
            _ => return mismatch(conv, arg),
        },
        'f' | 'e' | 'E' | 'g' | 'G' => {
            let v = match number(arg) {
                Num::Float(v) => v,
                Num::Int(..) => return mismatch(conv, arg),
                Num::None if arg.is_null() => return Ok("null".to_string()),
                Num::None => return mismatch(conv, arg),
            };
            floating(conv, spec, v)
        }
        _ => return throw("java.util.UnknownFormatConversionException", Some(&format!("Conversion = '{conv}'"))),
    })
}

fn group(digits: &str) -> String {
    let mut out = String::new();
    let n = digits.len();
    for (i, ch) in digits.chars().enumerate() {
        if i > 0 && (n - i) % 3 == 0 {
            out.push(',');
        }
        out.push(ch);
    }
    out
}

fn sign_and_pad(spec: &Spec, negative: bool, body: String) -> String {
    let (prefix, suffix) = if negative {
        if spec.paren { ("(", ")") } else { ("-", "") }
    } else if spec.plus {
        ("+", "")
    } else if spec.space {
        (" ", "")
    } else {
        ("", "")
    };
    let s = format!("{prefix}{body}{suffix}");
    if spec.zero {
        if let Some(w) = spec.width {
            let len = s.chars().count();
            if w > len {
                return format!("{prefix}{}{body}{suffix}", "0".repeat(w - len));
            }
        }
    }
    s
}

fn integer(spec: &Spec, v: i128) -> String {
    let digits = v.unsigned_abs().to_string();
    let body = if spec.comma { group(&digits) } else { digits };
    sign_and_pad(spec, v < 0, body)
}

fn zero_pad(spec: &Spec, s: String, _negative: bool) -> String {
    if spec.zero {
        if let Some(w) = spec.width {
            let len = s.chars().count();
            if w > len {
                return format!("{}{s}", "0".repeat(w - len));
            }
        }
    }
    s
}

/// 最短の 10 進表現（仮数の数字列と、先頭の数字の位の指数）。例: 1.005 → ("1005", 0)。
fn shortest_digits(v: f64) -> (Vec<u8>, i32) {
    let s = format!("{:e}", v.abs());
    let (mant, exp) = s.split_once('e').unwrap();
    let exp: i32 = exp.parse().unwrap();
    let digits: Vec<u8> = mant.bytes().filter(|b| b.is_ascii_digit()).map(|b| b - b'0').collect();
    (digits, exp)
}

/// 数字列を、先頭から keep 桁に HALF_UP で丸める。桁が繰り上がったら指数を 1 増やす。
fn round_half_up(mut digits: Vec<u8>, exp: i32, keep: i64) -> (Vec<u8>, i32) {
    if keep < 0 {
        return (vec![0], exp);
    }
    let keep = keep as usize;
    if digits.len() <= keep {
        digits.resize(keep, 0);
        return (digits, exp);
    }
    let round_up = digits[keep] >= 5;
    digits.truncate(keep);
    if round_up {
        let mut i = keep;
        loop {
            if i == 0 {
                digits.insert(0, 1);
                return (digits, exp + 1);
            }
            i -= 1;
            if digits[i] == 9 {
                digits[i] = 0;
            } else {
                digits[i] += 1;
                break;
            }
        }
    }
    (digits, exp)
}

fn floating(conv: char, spec: &Spec, v: f64) -> String {
    if v.is_nan() {
        return "NaN".to_string();
    }
    if v.is_infinite() {
        return sign_and_pad(&Spec { zero: false, ..*spec_copy(spec) }, v < 0.0, "Infinity".to_string());
    }
    let prec = spec.precision.unwrap_or(6);
    let negative = v.is_sign_negative() && v != 0.0;
    let body = match conv {
        'f' => fixed(v, prec, spec.comma),
        'e' | 'E' => {
            let s = scientific(v, prec);
            if conv == 'E' { s.to_uppercase() } else { s }
        }
        _ => {
            // %g: 丸めた値が 10^-4 以上 10^precision 未満なら %f、それ以外は %e。
            let p = if prec == 0 { 1 } else { prec };
            let a = v.abs();
            let s = if a != 0.0 && (a < 1e-4 || a >= 10f64.powi(p as i32)) {
                scientific(v, p - 1)
            } else {
                let (_, exp) = shortest_digits(a);
                let decimals = (p as i32 - 1 - if a == 0.0 { 0 } else { exp }).max(0) as usize;
                fixed(v, decimals, spec.comma)
            };
            if conv == 'G' { s.to_uppercase() } else { s }
        }
    };
    sign_and_pad(spec, negative, body)
}

fn spec_copy(spec: &Spec) -> &Spec {
    spec
}

impl Clone for Spec {
    fn clone(&self) -> Spec {
        Spec { ..*self }
    }
}

impl Copy for Spec {}

/// 固定小数点表記（符号なし）。
fn fixed(v: f64, prec: usize, comma: bool) -> String {
    let a = v.abs();
    let (int_part, frac_part) = if a == 0.0 {
        ("0".to_string(), "0".repeat(prec))
    } else {
        let (digits, exp) = shortest_digits(a);
        // 小数点以下 prec 桁までに必要な桁数
        let keep = exp as i64 + 1 + prec as i64;
        let (d, e) = round_half_up(digits, exp, keep);
        if keep < 0 || d.iter().all(|&x| x == 0) && keep <= 0 {
            ("0".to_string(), "0".repeat(prec))
        } else {
            // 数字列 d は 10^e の位から始まる
            let mut int_s = String::new();
            let mut frac_s = String::new();
            let total_int = e + 1;
            for pos in 0..(total_int.max(0) as usize) {
                int_s.push((b'0' + *d.get(pos).unwrap_or(&0)) as char);
            }
            if int_s.is_empty() {
                int_s.push('0');
            }
            for k in 0..prec {
                let pos = total_int as i64 + k as i64;
                let digit = if pos < 0 { 0 } else { *d.get(pos as usize).unwrap_or(&0) };
                frac_s.push((b'0' + digit) as char);
            }
            (int_s, frac_s)
        }
    };
    let int_part = if comma { group(&int_part) } else { int_part };
    if prec == 0 {
        int_part
    } else {
        format!("{int_part}.{frac_part}")
    }
}

/// 指数表記（符号なし）: `1.234560e+01`。
fn scientific(v: f64, prec: usize) -> String {
    let a = v.abs();
    if a == 0.0 {
        return format!("{}e+00", if prec == 0 { "0".to_string() } else { format!("0.{}", "0".repeat(prec)) });
    }
    let (digits, exp) = shortest_digits(a);
    let (d, e) = round_half_up(digits, exp, prec as i64 + 1);
    let mut s = String::new();
    s.push((b'0' + d[0]) as char);
    if prec > 0 {
        s.push('.');
        for k in 1..=prec {
            s.push((b'0' + *d.get(k).unwrap_or(&0)) as char);
        }
    }
    let sign = if e < 0 { '-' } else { '+' };
    format!("{s}e{sign}{:02}", e.abs())
}

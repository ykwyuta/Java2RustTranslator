//! java.util.regex（Pattern / Matcher）と、String.matches / replaceAll / replaceFirst / split。
//!
//! Java の正規表現の構文を解析して、バックトラックで照合する。対応する構文:
//! 文字・エスケープ（`\t \n \xhh \uhhhh \0ooo \cX \Q..\E`）、`.`、文字クラス（範囲・否定・入れ子・`&&`・
//! `\d \w \s \h \v` と大文字の否定・`\p{..}`）、アンカー（`^ $ \b \B \A \z \Z`）、グループ（捕捉・名前付き・
//! 非捕捉・アトミック）、先読み・後読み、量指定子（最長・最短・強欲）、後方参照（`\1` `\k<name>`）、
//! フラグ（`(?imsxu)`・`Pattern.CASE_INSENSITIVE` など）。文字は Unicode のコードポイント単位で扱い、
//! start() / end() は Java と同じく UTF-16 の位置で返す。

use crate::array::JArray;
use crate::lang::string::JString;
use crate::object::{alloc, JObject, Object, ObjectBase};
use crate::rt::{throw, JResult};
use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

// ------------------------------------------------------------------ フラグ（java.util.regex.Pattern の定数と同じ値）

pub const UNIX_LINES: i32 = 0x01;
pub const CASE_INSENSITIVE: i32 = 0x02;
pub const COMMENTS: i32 = 0x04;
pub const MULTILINE: i32 = 0x08;
pub const LITERAL: i32 = 0x10;
pub const DOTALL: i32 = 0x20;
pub const UNICODE_CASE: i32 = 0x40;

// ------------------------------------------------------------------ 構文木

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Mode {
    Greedy,
    Lazy,
    Possessive,
}

#[derive(Clone)]
enum Item {
    Range(char, char),
    Pred(fn(char) -> bool, bool),
    Set(Box<CharSet>),
}

/// 文字クラス。items のどれかに一致し、and のすべてに一致する（negate なら反転）。
#[derive(Clone)]
struct CharSet {
    items: Vec<Item>,
    and: Vec<CharSet>,
    negate: bool,
}

impl CharSet {
    fn one(item: Item) -> CharSet {
        CharSet { items: vec![item], and: Vec::new(), negate: false }
    }

    fn matches(&self, c: char, ci: bool, unicode_case: bool) -> bool {
        let hit = |ch: char| {
            self.items.iter().any(|it| match it {
                Item::Range(a, b) => *a <= ch && ch <= *b,
                Item::Pred(f, neg) => f(ch) != *neg,
                Item::Set(s) => s.matches(ch, ci, unicode_case),
            })
        };
        let mut r = hit(c);
        if !r && ci {
            r = fold_variants(c, unicode_case).into_iter().any(hit);
        }
        r = r && self.and.iter().all(|s| s.matches(c, ci, unicode_case));
        r != self.negate
    }
}

fn fold_variants(c: char, unicode: bool) -> Vec<char> {
    if unicode {
        let mut v: Vec<char> = c.to_lowercase().chain(c.to_uppercase()).collect();
        v.retain(|x| *x != c);
        v
    } else if c.is_ascii_alphabetic() {
        vec![if c.is_ascii_lowercase() { c.to_ascii_uppercase() } else { c.to_ascii_lowercase() }]
    } else {
        Vec::new()
    }
}

fn chars_eq(a: char, b: char, ci: bool, unicode: bool) -> bool {
    a == b || (ci && fold_variants(a, unicode).contains(&b))
}

#[derive(Clone)]
enum Node {
    Empty,
    Lit(char, bool, bool),
    Any(bool, bool),
    Set(Box<CharSet>, bool, bool),
    Bol(bool, bool),
    Eol(bool, bool),
    WordBoundary(bool),
    BeginInput,
    EndInput,
    EndInputZ(bool),
    Group(Box<Node>, usize),
    Concat(Vec<Node>),
    Alt(Vec<Node>),
    Repeat(Box<Node>, usize, Option<usize>, Mode),
    BackRef(usize, bool, bool),
    Look(Box<Node>, bool, bool),
    Atomic(Box<Node>),
}

/// コンパイル済みの正規表現。
pub struct Regex {
    root: Node,
    groups: usize,
    names: Vec<(String, usize)>,
    source: String,
    flags: i32,
}

// ------------------------------------------------------------------ 構文解析

struct Parser<'a> {
    p: &'a [char],
    i: usize,
    flags: i32,
    groups: usize,
    names: Vec<(String, usize)>,
    source: &'a str,
}

fn syntax_error<T>(desc: &str, source: &str, index: usize) -> JResult<T> {
    let caret = if index > 0 { " ".repeat(index) } else { String::new() };
    let msg = format!("{desc} near index {index}\n{source}\n{caret}^");
    throw("java.util.regex.PatternSyntaxException", Some(&msg))
}

fn is_line_terminator(c: char, unix: bool) -> bool {
    if unix {
        return c == '\n';
    }
    matches!(c, '\n' | '\r' | '\u{85}' | '\u{2028}' | '\u{2029}')
}

fn is_word(c: char) -> bool {
    c == '_' || c.is_ascii_alphanumeric()
}

fn is_digit(c: char) -> bool {
    c.is_ascii_digit()
}

fn is_space(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r')
}

fn is_hspace(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\u{A0}' | '\u{1680}' | '\u{180e}' | '\u{2000}'..='\u{200a}' | '\u{202f}' | '\u{205f}' | '\u{3000}')
}

fn is_vspace(c: char) -> bool {
    matches!(c, '\n' | '\u{0B}' | '\u{0C}' | '\r' | '\u{85}' | '\u{2028}' | '\u{2029}')
}

fn property(name: &str) -> Option<fn(char) -> bool> {
    let name = name.strip_prefix("Is").unwrap_or(name);
    let f: fn(char) -> bool = match name {
        "Lower" | "Ll" | "LowerCase" | "javaLowerCase" => |c| c.is_lowercase(),
        "Upper" | "Lu" | "UpperCase" | "javaUpperCase" => |c| c.is_uppercase(),
        "ASCII" => |c| c.is_ascii(),
        "Alpha" => |c| c.is_ascii_alphabetic(),
        "Digit" => |c| c.is_ascii_digit(),
        "Alnum" => |c| c.is_ascii_alphanumeric(),
        "Punct" => |c| c.is_ascii_punctuation(),
        "Graph" => |c| c.is_ascii_graphic(),
        "Print" => |c| c.is_ascii_graphic() || c == ' ',
        "Blank" => |c| c == ' ' || c == '\t',
        "Cntrl" => |c| c.is_ascii_control(),
        "XDigit" => |c| c.is_ascii_hexdigit(),
        "Space" | "javaWhitespace" | "White_Space" | "WhiteSpace" => |c| c.is_whitespace(),
        "L" | "Letter" | "Alphabetic" | "javaLetter" => |c| c.is_alphabetic(),
        "N" | "Nd" | "javaDigit" => |c| c.is_numeric(),
        "javaLetterOrDigit" => |c| c.is_alphanumeric(),
        "P" | "Punctuation" => |c| c.is_ascii_punctuation() || (!c.is_alphanumeric() && !c.is_whitespace() && !c.is_control() && !c.is_ascii()),
        "Latin" => |c| c.is_ascii_alphabetic() || ('\u{C0}'..='\u{24F}').contains(&c),
        "Hiragana" => |c| ('\u{3040}'..='\u{309F}').contains(&c),
        "Katakana" => |c| ('\u{30A0}'..='\u{30FF}').contains(&c),
        "Han" => |c| ('\u{4E00}'..='\u{9FFF}').contains(&c) || ('\u{3400}'..='\u{4DBF}').contains(&c),
        _ => return None,
    };
    Some(f)
}

impl<'a> Parser<'a> {
    fn peek(&self) -> Option<char> {
        self.p.get(self.i).copied()
    }

    fn peek_at(&self, k: usize) -> Option<char> {
        self.p.get(self.i + k).copied()
    }

    fn eat(&mut self, c: char) -> bool {
        if self.peek() == Some(c) {
            self.i += 1;
            true
        } else {
            false
        }
    }

    fn ci(&self) -> bool {
        self.flags & CASE_INSENSITIVE != 0
    }

    fn uc(&self) -> bool {
        self.flags & UNICODE_CASE != 0
    }

    fn err<T>(&self, desc: &str) -> JResult<T> {
        syntax_error(desc, self.source, self.i)
    }

    fn skip_comments(&mut self) {
        if self.flags & COMMENTS == 0 {
            return;
        }
        loop {
            match self.peek() {
                Some(c) if c.is_whitespace() => self.i += 1,
                Some('#') => {
                    while let Some(c) = self.peek() {
                        self.i += 1;
                        if is_line_terminator(c, false) {
                            break;
                        }
                    }
                }
                _ => return,
            }
        }
    }

    fn alternation(&mut self) -> JResult<Node> {
        let saved = self.flags;
        let mut alts = vec![self.sequence()?];
        while self.eat('|') {
            alts.push(self.sequence()?);
        }
        self.flags = saved;
        Ok(if alts.len() == 1 { alts.pop().unwrap_or(Node::Empty) } else { Node::Alt(alts) })
    }

    fn sequence(&mut self) -> JResult<Node> {
        let mut items = Vec::new();
        loop {
            self.skip_comments();
            match self.peek() {
                None | Some('|') | Some(')') => break,
                _ => {}
            }
            let atom = self.atom()?;
            if let Some(a) = atom {
                let q = self.quantifier(a)?;
                items.push(q);
            }
        }
        Ok(match items.len() {
            0 => Node::Empty,
            1 => items.pop().unwrap_or(Node::Empty),
            _ => Node::Concat(items),
        })
    }

    fn number(&mut self) -> Option<usize> {
        let start = self.i;
        while matches!(self.peek(), Some(c) if c.is_ascii_digit()) {
            self.i += 1;
        }
        if start == self.i {
            return None;
        }
        self.p[start..self.i].iter().collect::<String>().parse().ok()
    }

    fn quantifier(&mut self, atom: Node) -> JResult<Node> {
        self.skip_comments();
        let (min, max) = match self.peek() {
            Some('*') => {
                self.i += 1;
                (0, None)
            }
            Some('+') => {
                self.i += 1;
                (1, None)
            }
            Some('?') => {
                self.i += 1;
                (0, Some(1))
            }
            Some('{') => {
                let save = self.i;
                self.i += 1;
                let Some(lo) = self.number() else {
                    self.i = save;
                    return self.err("Illegal repetition");
                };
                let hi = if self.eat(',') { self.number() } else { Some(lo) };
                if !self.eat('}') {
                    return self.err("Unclosed counted closure");
                }
                if let Some(h) = hi {
                    if h < lo {
                        return self.err("Illegal repetition range");
                    }
                }
                (lo, hi)
            }
            _ => return Ok(atom),
        };
        let mode = if self.eat('?') {
            Mode::Lazy
        } else if self.eat('+') {
            Mode::Possessive
        } else {
            Mode::Greedy
        };
        if matches!(atom, Node::BeginInput | Node::Bol(..) | Node::Eol(..) | Node::EndInput | Node::EndInputZ(_)) {
            return Ok(atom);
        }
        Ok(Node::Repeat(Box::new(atom), min, max, mode))
    }

    fn lit(&self, c: char) -> Node {
        Node::Lit(c, self.ci(), self.uc())
    }

    fn atom(&mut self) -> JResult<Option<Node>> {
        let c = match self.peek() {
            Some(c) => c,
            None => return Ok(None),
        };
        self.i += 1;
        Ok(Some(match c {
            '.' => Node::Any(self.flags & DOTALL != 0, self.flags & UNIX_LINES != 0),
            '^' => Node::Bol(self.flags & MULTILINE != 0, self.flags & UNIX_LINES != 0),
            '$' => Node::Eol(self.flags & MULTILINE != 0, self.flags & UNIX_LINES != 0),
            '[' => {
                let set = self.class()?;
                Node::Set(Box::new(set), self.ci(), self.uc())
            }
            '(' => return self.group(),
            '*' | '+' | '?' => {
                self.i -= 1;
                return self.err(&format!("Dangling meta character '{c}'"));
            }
            '{' => {
                self.i -= 1;
                return self.err("Illegal repetition");
            }
            '\\' => return self.escape_atom(),
            c => self.lit(c),
        }))
    }

    fn group(&mut self) -> JResult<Option<Node>> {
        let saved = self.flags;
        let node = if self.eat('?') {
            match self.peek() {
                Some(':') => {
                    self.i += 1;
                    let n = self.alternation()?;
                    n
                }
                Some('=') | Some('!') => {
                    let neg = self.peek() == Some('!');
                    self.i += 1;
                    Node::Look(Box::new(self.alternation()?), true, neg)
                }
                Some('>') => {
                    self.i += 1;
                    Node::Atomic(Box::new(self.alternation()?))
                }
                Some('<') if matches!(self.peek_at(1), Some('=') | Some('!')) => {
                    let neg = self.peek_at(1) == Some('!');
                    self.i += 2;
                    Node::Look(Box::new(self.alternation()?), false, neg)
                }
                Some('<') => {
                    self.i += 1;
                    let start = self.i;
                    while matches!(self.peek(), Some(c) if c.is_ascii_alphanumeric()) {
                        self.i += 1;
                    }
                    let name: String = self.p[start..self.i].iter().collect();
                    if name.is_empty() || !self.eat('>') {
                        return self.err("named capturing group is missing trailing '>'");
                    }
                    if self.names.iter().any(|(n, _)| *n == name) {
                        return self.err(&format!("Named capturing group <{name}> is already defined"));
                    }
                    self.groups += 1;
                    let idx = self.groups;
                    self.names.push((name, idx));
                    Node::Group(Box::new(self.alternation()?), idx)
                }
                _ => {
                    // インラインのフラグ (?imsx-imsx) / (?i:...)
                    let mut on = true;
                    loop {
                        let f = match self.peek() {
                            Some('i') => CASE_INSENSITIVE,
                            Some('m') => MULTILINE,
                            Some('s') => DOTALL,
                            Some('x') => COMMENTS,
                            Some('u') => UNICODE_CASE,
                            Some('d') => UNIX_LINES,
                            Some('U') => 0,
                            Some('-') => {
                                on = false;
                                self.i += 1;
                                continue;
                            }
                            Some(')') => {
                                self.i += 1;
                                // (?i) はグループの外まで効く。
                                return Ok(Some(Node::Empty));
                            }
                            Some(':') => {
                                self.i += 1;
                                let n = self.alternation()?;
                                if !self.eat(')') {
                                    return self.err("Unclosed group");
                                }
                                self.flags = saved;
                                return Ok(Some(n));
                            }
                            _ => return self.err("Unknown inline modifier"),
                        };
                        self.flags = if on { self.flags | f } else { self.flags & !f };
                        self.i += 1;
                    }
                }
            }
        } else {
            self.groups += 1;
            let idx = self.groups;
            Node::Group(Box::new(self.alternation()?), idx)
        };
        if !self.eat(')') {
            return self.err("Unclosed group");
        }
        self.flags = saved;
        Ok(Some(node))
    }

    /// `\` の後（文字クラスの外）。
    fn escape_atom(&mut self) -> JResult<Option<Node>> {
        let Some(c) = self.peek() else { return self.err("Unexpected internal error") };
        self.i += 1;
        let ci = self.ci();
        let uc = self.uc();
        let set = |item: Item| Node::Set(Box::new(CharSet::one(item)), ci, uc);
        Ok(Some(match c {
            'b' => Node::WordBoundary(false),
            'B' => Node::WordBoundary(true),
            'A' => Node::BeginInput,
            'z' => Node::EndInput,
            'Z' => Node::EndInputZ(self.flags & UNIX_LINES != 0),
            'G' => Node::BeginInput,
            'R' => Node::Alt(vec![
                Node::Concat(vec![Node::Lit('\r', false, false), Node::Lit('\n', false, false)]),
                set(Item::Pred(is_vspace, false)),
            ]),
            'Q' => {
                let start = self.i;
                let mut end = self.p.len();
                let mut k = self.i;
                while k + 1 < self.p.len() {
                    if self.p[k] == '\\' && self.p[k + 1] == 'E' {
                        end = k;
                        break;
                    }
                    k += 1;
                }
                let lits: Vec<Node> = self.p[start..end].iter().map(|&c| self.lit(c)).collect();
                self.i = if end == self.p.len() { end } else { end + 2 };
                Node::Concat(lits)
            }
            'k' => {
                if !self.eat('<') {
                    return self.err("\\k is not followed by '<' for named capturing group");
                }
                let start = self.i;
                while matches!(self.peek(), Some(c) if c.is_ascii_alphanumeric()) {
                    self.i += 1;
                }
                let name: String = self.p[start..self.i].iter().collect();
                if !self.eat('>') {
                    return self.err("named capturing group is missing trailing '>'");
                }
                match self.names.iter().find(|(n, _)| *n == name) {
                    Some((_, idx)) => Node::BackRef(*idx, ci, uc),
                    None => return self.err(&format!("named capturing group <{name}> does not exist")),
                }
            }
            '1'..='9' => {
                let mut n = c.to_digit(10).unwrap_or(0) as usize;
                // 存在するグループの番号になる限り、数字を続けて読む（Java と同じ）。
                while let Some(d) = self.peek().and_then(|x| x.to_digit(10)) {
                    let m = n * 10 + d as usize;
                    if m > self.groups {
                        break;
                    }
                    n = m;
                    self.i += 1;
                }
                Node::BackRef(n, ci, uc)
            }
            _ => {
                self.i -= 1;
                match self.escape_item()? {
                    Item::Range(a, b) if a == b => self.lit(a),
                    item => set(item),
                }
            }
        }))
    }

    /// 文字・文字クラスを表すエスケープ（文字クラスの内外で共通）。
    fn escape_item(&mut self) -> JResult<Item> {
        let Some(c) = self.peek() else { return self.err("Unexpected internal error") };
        self.i += 1;
        let ch = |c: char| Item::Range(c, c);
        Ok(match c {
            'd' => Item::Pred(is_digit, false),
            'D' => Item::Pred(is_digit, true),
            'w' => Item::Pred(is_word, false),
            'W' => Item::Pred(is_word, true),
            's' => Item::Pred(is_space, false),
            'S' => Item::Pred(is_space, true),
            'h' => Item::Pred(is_hspace, false),
            'H' => Item::Pred(is_hspace, true),
            'v' => Item::Pred(is_vspace, false),
            'V' => Item::Pred(is_vspace, true),
            't' => ch('\t'),
            'n' => ch('\n'),
            'r' => ch('\r'),
            'f' => ch('\u{0C}'),
            'a' => ch('\u{07}'),
            'e' => ch('\u{1B}'),
            '0' => {
                let mut v = 0u32;
                let mut k = 0;
                while k < 3 {
                    match self.peek().and_then(|x| x.to_digit(8)) {
                        Some(d) if v * 8 + d <= 0o377 => {
                            v = v * 8 + d;
                            self.i += 1;
                            k += 1;
                        }
                        _ => break,
                    }
                }
                if k == 0 {
                    return self.err("Illegal octal escape sequence");
                }
                ch(char::from_u32(v).unwrap_or('\0'))
            }
            'x' => {
                let v = if self.eat('{') {
                    let start = self.i;
                    while matches!(self.peek(), Some(c) if c.is_ascii_hexdigit()) {
                        self.i += 1;
                    }
                    let s: String = self.p[start..self.i].iter().collect();
                    if !self.eat('}') {
                        return self.err("Unclosed hexadecimal escape sequence");
                    }
                    u32::from_str_radix(&s, 16).ok()
                } else {
                    let s: String = self.p.get(self.i..self.i + 2).map(|x| x.iter().collect()).unwrap_or_default();
                    self.i += 2;
                    u32::from_str_radix(&s, 16).ok()
                };
                match v.and_then(char::from_u32) {
                    Some(c) => ch(c),
                    None => return self.err("Illegal hexadecimal escape sequence"),
                }
            }
            'u' => {
                let s: String = self.p.get(self.i..self.i + 4).map(|x| x.iter().collect()).unwrap_or_default();
                self.i += 4;
                match u32::from_str_radix(&s, 16).ok().and_then(char::from_u32) {
                    Some(c) => ch(c),
                    None => return self.err("Illegal Unicode escape sequence"),
                }
            }
            'c' => {
                let Some(x) = self.peek() else { return self.err("Illegal control escape sequence") };
                self.i += 1;
                ch(char::from_u32(x as u32 ^ 64).unwrap_or('\0'))
            }
            'p' | 'P' => {
                let name: String = if self.eat('{') {
                    let start = self.i;
                    while matches!(self.peek(), Some(c) if c != '}') {
                        self.i += 1;
                    }
                    let n: String = self.p[start..self.i].iter().collect();
                    if !self.eat('}') {
                        return self.err("Unclosed character family");
                    }
                    n
                } else {
                    let Some(x) = self.peek() else { return self.err("Illegal character family") };
                    self.i += 1;
                    x.to_string()
                };
                match property(&name) {
                    Some(f) => Item::Pred(f, c == 'P'),
                    None => return self.err(&format!("Unknown character property name {{{name}}}")),
                }
            }
            c if c.is_ascii_alphanumeric() => {
                self.i -= 1;
                return self.err("Illegal/unsupported escape sequence");
            }
            c => ch(c),
        })
    }

    /// `[` の後: 文字クラス。
    fn class(&mut self) -> JResult<CharSet> {
        let mut set = CharSet { items: Vec::new(), and: Vec::new(), negate: false };
        if self.eat('^') {
            set.negate = true;
        }
        let mut first = true;
        loop {
            let Some(c) = self.peek() else { return self.err("Unclosed character class") };
            if c == ']' && !first {
                self.i += 1;
                break;
            }
            first = false;
            if c == '[' {
                self.i += 1;
                let nested = self.class()?;
                set.items.push(Item::Set(Box::new(nested)));
                continue;
            }
            if c == '&' && self.peek_at(1) == Some('&') {
                self.i += 2;
                // && の右側: 残り（] まで）を 1 つの文字クラスとして読む。
                let mut rhs = CharSet { items: Vec::new(), and: Vec::new(), negate: false };
                loop {
                    match self.peek() {
                        None => return self.err("Unclosed character class"),
                        Some(']') => break,
                        Some('[') => {
                            self.i += 1;
                            let nested = self.class()?;
                            rhs.items.push(Item::Set(Box::new(nested)));
                        }
                        Some('&') if self.peek_at(1) == Some('&') => {
                            self.i += 2;
                            let prev = std::mem::replace(&mut rhs, CharSet { items: Vec::new(), and: Vec::new(), negate: false });
                            set.and.push(prev);
                        }
                        Some(_) => {
                            let item = self.class_item()?;
                            rhs.items.push(item);
                        }
                    }
                }
                set.and.push(rhs);
                continue;
            }
            let item = self.class_item()?;
            set.items.push(item);
        }
        let empty_left = set.items.is_empty() && !set.and.is_empty();
        if empty_left {
            // [&&[a-z]] のような左辺が空の積は、右辺そのもの。
            let mut and = std::mem::take(&mut set.and);
            let first = and.remove(0);
            set.items.push(Item::Set(Box::new(first)));
            set.and = and;
        }
        Ok(set)
    }

    /// 文字クラスの中の 1 要素（範囲を含む）。
    fn class_item(&mut self) -> JResult<Item> {
        let start = self.single_class_char()?;
        let Item::Range(lo, _) = start else { return Ok(start) };
        if self.peek() == Some('-') && !matches!(self.peek_at(1), Some(']') | None) && self.peek_at(1) != Some('[') {
            self.i += 1;
            let end = self.single_class_char()?;
            let Item::Range(hi, _) = end else { return self.err("Illegal character range") };
            if hi < lo {
                return self.err("Illegal character range");
            }
            return Ok(Item::Range(lo, hi));
        }
        Ok(start)
    }

    fn single_class_char(&mut self) -> JResult<Item> {
        let Some(c) = self.peek() else { return self.err("Unclosed character class") };
        self.i += 1;
        if c == '\\' {
            if self.peek() == Some('Q') {
                // \Q..\E の中の文字（クラスの中では最初の 1 文字ずつ）
                self.i += 1;
                let mut items = Vec::new();
                while self.i < self.p.len() && !(self.p[self.i] == '\\' && self.peek_at(1) == Some('E')) {
                    items.push(Item::Range(self.p[self.i], self.p[self.i]));
                    self.i += 1;
                }
                self.i = (self.i + 2).min(self.p.len());
                return Ok(Item::Set(Box::new(CharSet { items, and: Vec::new(), negate: false })));
            }
            return self.escape_item();
        }
        Ok(Item::Range(c, c))
    }
}

/// 正規表現をコンパイルする（構文エラーは PatternSyntaxException）。
pub fn compile_regex(pattern: &str, flags: i32) -> JResult<Regex> {
    let chars: Vec<char> = pattern.chars().collect();
    if flags & LITERAL != 0 {
        let lits = chars.iter().map(|&c| Node::Lit(c, flags & CASE_INSENSITIVE != 0, flags & UNICODE_CASE != 0)).collect();
        return Ok(Regex { root: Node::Concat(lits), groups: 0, names: Vec::new(), source: pattern.to_string(), flags });
    }
    let mut p = Parser { p: &chars, i: 0, flags, groups: 0, names: Vec::new(), source: pattern };
    let root = p.alternation()?;
    if p.i < chars.len() {
        return p.err(if chars[p.i] == ')' { "Unmatched closing ')'" } else { "Unexpected character" });
    }
    Ok(Regex { root, groups: p.groups, names: p.names, source: pattern.to_string(), flags })
}

// ------------------------------------------------------------------ 照合

type Caps = Vec<Option<(usize, usize)>>;

struct Ctx<'a> {
    t: &'a [char],
    caps: Caps,
}

type Cont<'k> = &'k mut dyn FnMut(usize, &mut Ctx) -> bool;

fn at_word(t: &[char], i: usize) -> bool {
    i < t.len() && (t[i] == '_' || t[i].is_alphanumeric())
}

impl Regex {
    fn m(&self, n: &Node, i: usize, cx: &mut Ctx, k: Cont) -> bool {
        let t = cx.t;
        match n {
            Node::Empty => k(i, cx),
            Node::Lit(c, ci, uc) => i < t.len() && chars_eq(*c, t[i], *ci, *uc) && k(i + 1, cx),
            Node::Any(dotall, unix) => i < t.len() && (*dotall || !is_line_terminator(t[i], *unix)) && k(i + 1, cx),
            Node::Set(s, ci, uc) => i < t.len() && s.matches(t[i], *ci, *uc) && k(i + 1, cx),
            Node::Bol(multi, unix) => {
                let ok = i == 0 || (*multi && i < t.len() && is_line_terminator(t[i - 1], *unix)
                    && !(t[i - 1] == '\r' && t[i] == '\n'));
                ok && k(i, cx)
            }
            Node::Eol(multi, unix) => {
                let ok = if *multi {
                    i == t.len() || (is_line_terminator(t[i], *unix) && !(i > 0 && t[i - 1] == '\r' && t[i] == '\n'))
                } else {
                    end_z(t, i, *unix)
                };
                ok && k(i, cx)
            }
            Node::WordBoundary(neg) => {
                let b = (i > 0 && at_word(t, i - 1)) != at_word(t, i);
                (b != *neg) && k(i, cx)
            }
            Node::BeginInput => i == 0 && k(i, cx),
            Node::EndInput => i == t.len() && k(i, cx),
            Node::EndInputZ(unix) => end_z(t, i, *unix) && k(i, cx),
            Node::Group(inner, idx) => {
                let idx = *idx;
                self.m(inner, i, cx, &mut |j, cx: &mut Ctx| {
                    let prev = cx.caps[idx];
                    cx.caps[idx] = Some((i, j));
                    if k(j, cx) {
                        true
                    } else {
                        cx.caps[idx] = prev;
                        false
                    }
                })
            }
            Node::Concat(items) => self.seq(items, i, cx, k),
            Node::Alt(alts) => {
                for a in alts {
                    if self.m(a, i, cx, &mut *k) {
                        return true;
                    }
                }
                false
            }
            Node::Repeat(inner, min, max, mode) => match mode {
                Mode::Greedy => self.greedy(inner, *min, *max, 0, i, cx, k),
                Mode::Lazy => self.lazy(inner, *min, *max, 0, i, cx, k),
                Mode::Possessive => {
                    let mut pos = i;
                    let mut count = 0usize;
                    while max.map_or(true, |mx| count < mx) {
                        let mut end = None;
                        if !self.m(inner, pos, cx, &mut |j, _| {
                            end = Some(j);
                            true
                        }) {
                            break;
                        }
                        let j = end.unwrap_or(pos);
                        if j == pos && count >= *min {
                            break;
                        }
                        pos = j;
                        count += 1;
                    }
                    count >= *min && k(pos, cx)
                }
            },
            Node::BackRef(g, ci, uc) => {
                let Some(Some((s, e))) = cx.caps.get(*g).copied() else { return false };
                let len = e - s;
                if i + len > t.len() {
                    return false;
                }
                (0..len).all(|d| chars_eq(t[s + d], t[i + d], *ci, *uc)) && k(i + len, cx)
            }
            Node::Look(inner, ahead, neg) => {
                let saved = cx.caps.clone();
                let found = if *ahead {
                    self.m(inner, i, cx, &mut |_, _| true)
                } else {
                    (0..=i).rev().any(|s| self.m(inner, s, cx, &mut |j, _| j == i))
                };
                if *neg {
                    cx.caps = saved;
                    !found && k(i, cx)
                } else if found && k(i, cx) {
                    true
                } else {
                    cx.caps = saved;
                    false
                }
            }
            Node::Atomic(inner) => {
                let mut end = None;
                if self.m(inner, i, cx, &mut |j, _| {
                    end = Some(j);
                    true
                }) {
                    k(end.unwrap_or(i), cx)
                } else {
                    false
                }
            }
        }
    }

    fn seq(&self, items: &[Node], i: usize, cx: &mut Ctx, k: Cont) -> bool {
        match items.split_first() {
            None => k(i, cx),
            Some((first, rest)) => self.m(first, i, cx, &mut |j, cx: &mut Ctx| self.seq(rest, j, cx, &mut *k)),
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn greedy(&self, n: &Node, min: usize, max: Option<usize>, count: usize, i: usize, cx: &mut Ctx, k: Cont) -> bool {
        if max.map_or(true, |mx| count < mx)
            && self.m(n, i, cx, &mut |j, cx: &mut Ctx| {
                if j == i && count >= min {
                    return false;
                }
                self.greedy(n, min, max, count + 1, j, cx, &mut *k)
            })
        {
            return true;
        }
        count >= min && k(i, cx)
    }

    #[allow(clippy::too_many_arguments)]
    fn lazy(&self, n: &Node, min: usize, max: Option<usize>, count: usize, i: usize, cx: &mut Ctx, k: Cont) -> bool {
        if count >= min && k(i, cx) {
            return true;
        }
        max.map_or(true, |mx| count < mx)
            && self.m(n, i, cx, &mut |j, cx: &mut Ctx| {
                if j == i && count >= min {
                    return false;
                }
                self.lazy(n, min, max, count + 1, j, cx, &mut *k)
            })
    }

    /// 位置 start から照合する。anchored_end なら入力の最後まで一致する必要がある。
    fn match_at(&self, t: &[char], start: usize, anchored_end: bool) -> Option<Caps> {
        let mut cx = Ctx { t, caps: vec![None; self.groups + 1] };
        let len = t.len();
        let mut end = None;
        let ok = self.m(&self.root, start, &mut cx, &mut |j, _| {
            if anchored_end && j != len {
                return false;
            }
            end = Some(j);
            true
        });
        if !ok {
            return None;
        }
        cx.caps[0] = Some((start, end.unwrap_or(start)));
        Some(cx.caps)
    }

    /// from 以降で最初に一致する位置。
    fn search(&self, t: &[char], from: usize) -> Option<Caps> {
        (from..=t.len()).find_map(|s| self.match_at(t, s, false))
    }

    fn group_index(&self, name: &str) -> Option<usize> {
        self.names.iter().find(|(n, _)| n == name).map(|(_, i)| *i)
    }
}

fn end_z(t: &[char], i: usize, unix: bool) -> bool {
    let len = t.len();
    i == len
        || (i + 1 == len && is_line_terminator(t[i], unix))
        || (i + 2 == len && t[i] == '\r' && t[i + 1] == '\n' && !unix)
}

// ------------------------------------------------------------------ Pattern / Matcher

thread_local! {
    static CACHE: RefCell<HashMap<(String, i32), Rc<Regex>>> = RefCell::new(HashMap::new());
}

fn cached(pattern: &str, flags: i32) -> JResult<Rc<Regex>> {
    let key = (pattern.to_string(), flags);
    if let Some(r) = CACHE.with(|c| c.borrow().get(&key).cloned()) {
        return Ok(r);
    }
    let r = Rc::new(compile_regex(pattern, flags)?);
    CACHE.with(|c| c.borrow_mut().insert(key, r.clone()));
    Ok(r)
}

pub struct JPattern {
    base: ObjectBase,
    re: Rc<Regex>,
}

impl Object for JPattern {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        "java.util.regex.Pattern"
    }
    fn to_jstring(&self) -> JResult<JString> {
        Ok(JString::from(self.re.source.clone()))
    }
}

/// `Pattern.compile(regex)` / `Pattern.compile(regex, flags)`。
pub fn compile(pattern: &JString, flags: i32) -> JResult<JObject> {
    let re = cached(pattern.as_str()?, flags)?;
    Ok(alloc(|base| JPattern { base, re }))
}

fn pattern_of(p: &JObject) -> JResult<Rc<Regex>> {
    match p.downcast_ref::<JPattern>() {
        Some(x) => Ok(x.re.clone()),
        None => {
            p.obj()?;
            crate::object::class_cast(p, "java.util.regex.Pattern")
        }
    }
}

/// `pattern.pattern()`。
pub fn pattern_source(p: &JObject) -> JResult<JString> {
    Ok(JString::from(pattern_of(p)?.source.clone()))
}

/// `pattern.flags()`。
pub fn pattern_flags(p: &JObject) -> JResult<i32> {
    Ok(pattern_of(p)?.flags)
}

/// `Pattern.quote(s)`。
pub fn quote(s: &JString) -> JResult<JString> {
    let s = s.as_str()?;
    if !s.contains("\\E") {
        return Ok(JString::from(format!("\\Q{s}\\E")));
    }
    Ok(JString::from(format!("\\Q{}\\E", s.replace("\\E", "\\E\\\\E\\Q"))))
}

struct MatcherState {
    re: Rc<Regex>,
    text: Vec<char>,
    utf16: Vec<usize>,
    caps: Option<Caps>,
    /// 次の find の開始位置と、直前の一致の範囲。
    first: Option<usize>,
    last: usize,
    append_pos: usize,
}

pub struct JMatcher {
    base: ObjectBase,
    st: RefCell<MatcherState>,
}

impl Object for JMatcher {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        "java.util.regex.Matcher"
    }
}

fn utf16_offsets(t: &[char]) -> Vec<usize> {
    let mut out = Vec::with_capacity(t.len() + 1);
    let mut n = 0;
    out.push(0);
    for c in t {
        n += c.len_utf16();
        out.push(n);
    }
    out
}

fn new_state(re: Rc<Regex>, s: &str) -> MatcherState {
    let text: Vec<char> = s.chars().collect();
    let utf16 = utf16_offsets(&text);
    MatcherState { re, text, utf16, caps: None, first: None, last: 0, append_pos: 0 }
}

/// `pattern.matcher(s)`。
pub fn matcher(p: &JObject, s: &JString) -> JResult<JObject> {
    let st = new_state(pattern_of(p)?, s.as_str()?);
    Ok(alloc(|base| JMatcher { base, st: RefCell::new(st) }))
}

fn mstate(m: &JObject) -> JResult<&RefCell<MatcherState>> {
    match m.downcast_ref::<JMatcher>() {
        Some(x) => Ok(&x.st),
        None => {
            m.obj()?;
            crate::object::class_cast(m, "java.util.regex.Matcher")
        }
    }
}

/// `matcher.matches()`。
pub fn matches(m: &JObject) -> JResult<bool> {
    let mut st = mstate(m)?.borrow_mut();
    let caps = st.re.match_at(&st.text, 0, true);
    st.first = caps.as_ref().map(|c| c[0].map_or(0, |x| x.0));
    st.last = caps.as_ref().map_or(0, |c| c[0].map_or(0, |x| x.1));
    let ok = caps.is_some();
    st.caps = caps;
    Ok(ok)
}

/// `matcher.lookingAt()`。
pub fn looking_at(m: &JObject) -> JResult<bool> {
    let mut st = mstate(m)?.borrow_mut();
    let caps = st.re.match_at(&st.text, 0, false);
    st.first = caps.as_ref().map(|c| c[0].map_or(0, |x| x.0));
    st.last = caps.as_ref().map_or(0, |c| c[0].map_or(0, |x| x.1));
    let ok = caps.is_some();
    st.caps = caps;
    Ok(ok)
}

fn find_in(st: &mut MatcherState) -> bool {
    let mut from = st.last;
    if st.first == Some(from) {
        // 直前が空の一致なら 1 文字進める（Java と同じ）。
        from += 1;
    }
    if from > st.text.len() {
        st.caps = None;
        return false;
    }
    let caps = st.re.search(&st.text, from);
    match caps {
        Some(c) => {
            let (s, e) = c[0].unwrap_or((from, from));
            st.first = Some(s);
            st.last = e;
            st.caps = Some(c);
            true
        }
        None => {
            st.caps = None;
            false
        }
    }
}

/// `matcher.find()`。
pub fn find(m: &JObject) -> JResult<bool> {
    let mut st = mstate(m)?.borrow_mut();
    Ok(find_in(&mut st))
}

/// `matcher.find(start)`（start は UTF-16 の位置）。
pub fn find_from(m: &JObject, start: i32) -> JResult<bool> {
    let mut st = mstate(m)?.borrow_mut();
    let len16 = *st.utf16.last().unwrap_or(&0);
    if start < 0 || start as usize > len16 {
        return throw("java.lang.IndexOutOfBoundsException", Some("Illegal start index"));
    }
    let ci = st.utf16.iter().position(|&x| x >= start as usize).unwrap_or(st.text.len());
    st.first = None;
    st.last = ci;
    st.append_pos = 0;
    Ok(find_in(&mut st))
}

/// `matcher.reset()`。
pub fn reset(m: &JObject) -> JResult<JObject> {
    let mut st = mstate(m)?.borrow_mut();
    st.caps = None;
    st.first = None;
    st.last = 0;
    st.append_pos = 0;
    Ok(m.clone())
}

/// `matcher.reset(input)`。
pub fn reset_input(m: &JObject, s: &JString) -> JResult<JObject> {
    let cell = mstate(m)?;
    let re = cell.borrow().re.clone();
    *cell.borrow_mut() = new_state(re, s.as_str()?);
    Ok(m.clone())
}

fn current(st: &MatcherState) -> JResult<&Caps> {
    match &st.caps {
        Some(c) => Ok(c),
        None => throw("java.lang.IllegalStateException", Some("No match found")),
    }
}

fn group_range(st: &MatcherState, g: usize) -> JResult<Option<(usize, usize)>> {
    let caps = current(st)?;
    if g >= caps.len() {
        return throw("java.lang.IndexOutOfBoundsException", Some(&format!("No group {g}")));
    }
    Ok(caps[g])
}

/// `matcher.group()` / `group(n)`（一致しなかったグループは null）。
pub fn group(m: &JObject, g: i32) -> JResult<JString> {
    let st = mstate(m)?.borrow();
    if g < 0 {
        return throw("java.lang.IndexOutOfBoundsException", Some(&format!("No group {g}")));
    }
    Ok(match group_range(&st, g as usize)? {
        Some((s, e)) => JString::from(st.text[s..e].iter().collect::<String>()),
        None => JString::null(),
    })
}

/// `matcher.group(name)`。
pub fn group_named(m: &JObject, name: &JString) -> JResult<JString> {
    let idx = {
        let st = mstate(m)?.borrow();
        match st.re.group_index(name.as_str()?) {
            Some(i) => i,
            None => return throw("java.lang.IllegalArgumentException", Some(&format!("No group with name <{}>", name.as_str()?))),
        }
    };
    group(m, idx as i32)
}

/// `matcher.groupCount()`。
pub fn group_count(m: &JObject) -> JResult<i32> {
    Ok(mstate(m)?.borrow().re.groups as i32)
}

/// `matcher.start()` / `start(n)`（UTF-16 の位置。一致しなかったグループは -1）。
pub fn start(m: &JObject, g: i32) -> JResult<i32> {
    let st = mstate(m)?.borrow();
    Ok(match group_range(&st, g.max(0) as usize)? {
        Some((s, _)) => st.utf16[s] as i32,
        None => -1,
    })
}

/// `matcher.end()` / `end(n)`。
pub fn end(m: &JObject, g: i32) -> JResult<i32> {
    let st = mstate(m)?.borrow();
    Ok(match group_range(&st, g.max(0) as usize)? {
        Some((_, e)) => st.utf16[e] as i32,
        None => -1,
    })
}

/// 置換文字列（`$1` `${name}` `\$`）を展開する。
fn expand_replacement(re: &Regex, t: &[char], caps: &Caps, repl: &str, out: &mut String) -> JResult<()> {
    let r: Vec<char> = repl.chars().collect();
    let mut i = 0;
    while i < r.len() {
        match r[i] {
            '\\' => {
                i += 1;
                if i >= r.len() {
                    return throw("java.lang.IllegalArgumentException", Some("character to be escaped is missing"));
                }
                out.push(r[i]);
                i += 1;
            }
            '$' => {
                i += 1;
                if i >= r.len() {
                    return throw("java.lang.IllegalArgumentException", Some("Illegal group reference: group index is missing"));
                }
                let g = if r[i] == '{' {
                    let start = i + 1;
                    let Some(close) = r[start..].iter().position(|&c| c == '}') else {
                        return throw("java.lang.IllegalArgumentException", Some("named capturing group is missing trailing '}'"));
                    };
                    let name: String = r[start..start + close].iter().collect();
                    i = start + close + 1;
                    match re.group_index(&name) {
                        Some(g) => g,
                        None => return throw("java.lang.IllegalArgumentException", Some(&format!("No group with name {{{name}}}"))),
                    }
                } else {
                    let Some(d) = r[i].to_digit(10) else {
                        return throw("java.lang.IllegalArgumentException", Some("Illegal group reference"));
                    };
                    let mut g = d as usize;
                    i += 1;
                    // 存在するグループの番号になる限り、数字を続けて読む。
                    while i < r.len() {
                        match r[i].to_digit(10) {
                            Some(d2) if g * 10 + (d2 as usize) <= re.groups => {
                                g = g * 10 + d2 as usize;
                                i += 1;
                            }
                            _ => break,
                        }
                    }
                    if g > re.groups {
                        return throw("java.lang.IndexOutOfBoundsException", Some(&format!("No group {g}")));
                    }
                    g
                };
                if let Some((s, e)) = caps[g] {
                    out.extend(&t[s..e]);
                }
            }
            c => {
                out.push(c);
                i += 1;
            }
        }
    }
    Ok(())
}

fn replace_impl(re: &Regex, s: &str, repl: &str, all: bool) -> JResult<String> {
    let text: Vec<char> = s.chars().collect();
    let mut out = String::new();
    let mut pos = 0;
    let mut first: Option<usize> = None;
    let mut last = 0;
    loop {
        let mut from = last;
        if first == Some(from) {
            from += 1;
        }
        if from > text.len() {
            break;
        }
        let Some(caps) = re.search(&text, from) else { break };
        let (s0, e0) = caps[0].unwrap_or((from, from));
        out.extend(&text[pos..s0]);
        expand_replacement(re, &text, &caps, repl, &mut out)?;
        pos = e0;
        first = Some(s0);
        last = e0;
        if !all {
            break;
        }
    }
    out.extend(&text[pos..]);
    Ok(out)
}

/// `matcher.replaceAll(replacement)`。
pub fn matcher_replace_all(m: &JObject, repl: &JString) -> JResult<JString> {
    let (re, text): (Rc<Regex>, String) = {
        let st = mstate(m)?.borrow();
        (st.re.clone(), st.text.iter().collect())
    };
    reset(m)?;
    Ok(JString::from(replace_impl(&re, &text, repl.as_str()?, true)?))
}

/// `matcher.replaceFirst(replacement)`。
pub fn matcher_replace_first(m: &JObject, repl: &JString) -> JResult<JString> {
    let (re, text): (Rc<Regex>, String) = {
        let st = mstate(m)?.borrow();
        (st.re.clone(), st.text.iter().collect())
    };
    reset(m)?;
    Ok(JString::from(replace_impl(&re, &text, repl.as_str()?, false)?))
}

/// `matcher.appendReplacement(sb, replacement)`。
pub fn append_replacement(m: &JObject, sb: &crate::lang::StringBuilder, repl: &JString) -> JResult<JObject> {
    let mut st = mstate(m)?.borrow_mut();
    let caps = current(&st)?.clone();
    let (s0, e0) = caps[0].unwrap_or((0, 0));
    let mut out: String = st.text[st.append_pos..s0].iter().collect();
    expand_replacement(&st.re, &st.text, &caps, repl.as_str()?, &mut out)?;
    sb.append(out.as_str());
    st.append_pos = e0;
    drop(st);
    Ok(m.clone())
}

/// `matcher.appendTail(sb)`。
pub fn append_tail(m: &JObject, sb: &crate::lang::StringBuilder) -> JResult<crate::lang::StringBuilder> {
    let st = mstate(m)?.borrow();
    let tail: String = st.text[st.append_pos..].iter().collect();
    sb.append(tail.as_str());
    Ok(sb.clone())
}

/// `matcher.hitEnd()` の近似: 直前の一致が入力の最後に達したか。
pub fn hit_end(m: &JObject) -> JResult<bool> {
    let st = mstate(m)?.borrow();
    Ok(st.last >= st.text.len())
}

/// `Pattern.matches(regex, s)` / `s.matches(regex)`。
pub fn string_matches(s: &JString, regex: &JString) -> JResult<bool> {
    let re = cached(regex.as_str()?, 0)?;
    let t: Vec<char> = s.as_str()?.chars().collect();
    Ok(re.match_at(&t, 0, true).is_some())
}

/// `s.replaceAll(regex, replacement)`。
pub fn replace_all(s: &JString, regex: &JString, repl: &JString) -> JResult<JString> {
    let re = cached(regex.as_str()?, 0)?;
    Ok(JString::from(replace_impl(&re, s.as_str()?, repl.as_str()?, true)?))
}

/// `s.replaceFirst(regex, replacement)`。
pub fn replace_first(s: &JString, regex: &JString, repl: &JString) -> JResult<JString> {
    let re = cached(regex.as_str()?, 0)?;
    Ok(JString::from(replace_impl(&re, s.as_str()?, repl.as_str()?, false)?))
}

/// `Matcher.quoteReplacement(s)`。
pub fn quote_replacement(s: &JString) -> JResult<JString> {
    let s = s.as_str()?;
    Ok(JString::from(s.replace('\\', "\\\\").replace('$', "\\$")))
}

/// Java の `Pattern.split(input, limit)` と同じ規則で分割する。
fn split_impl(re: &Regex, s: &str, limit: i32) -> Vec<String> {
    let t: Vec<char> = s.chars().collect();
    let mut list: Vec<String> = Vec::new();
    let mut index = 0usize;
    let limited = limit > 0;
    let mut first: Option<usize> = None;
    let mut last = 0usize;
    loop {
        let mut from = last;
        if first == Some(from) {
            from += 1;
        }
        if from > t.len() {
            break;
        }
        let Some(caps) = re.search(&t, from) else { break };
        let (ms, me) = caps[0].unwrap_or((from, from));
        first = Some(ms);
        last = me;
        if !limited || (list.len() as i32) < limit - 1 {
            if index == 0 && ms == 0 && ms == me {
                // 入力の先頭での幅 0 の一致では、空の先頭要素を作らない。
                continue;
            }
            list.push(t[index..ms].iter().collect());
            index = me;
        } else if list.len() as i32 == limit - 1 {
            list.push(t[index..].iter().collect());
            index = me;
        }
    }
    if index == 0 {
        return vec![s.to_string()];
    }
    if !limited || (list.len() as i32) < limit {
        list.push(t[index..].iter().collect());
    }
    if limit == 0 {
        while list.last().is_some_and(|x| x.is_empty()) {
            list.pop();
        }
    }
    list
}

/// `s.split(regex)` / `s.split(regex, limit)`。
pub fn split(s: &JString, regex: &JString, limit: i32) -> JResult<JArray<JString>> {
    let re = cached(regex.as_str()?, 0)?;
    Ok(JArray::from_vec(split_impl(&re, s.as_str()?, limit).into_iter().map(JString::from).collect()))
}

/// `pattern.split(s)` / `pattern.split(s, limit)`。
pub fn pattern_split(p: &JObject, s: &JString, limit: i32) -> JResult<JArray<JString>> {
    let re = pattern_of(p)?;
    Ok(JArray::from_vec(split_impl(&re, s.as_str()?, limit).into_iter().map(JString::from).collect()))
}

/// `pattern.matcher(s).matches()` の短縮（`Pattern.matches(regex, input)`）。
pub fn pattern_matches(regex: &JString, s: &JString) -> JResult<bool> {
    string_matches(s, regex)
}

/// `pattern.asPredicate()` / `asMatchPredicate()`。
pub fn as_predicate(p: &JObject, whole: bool) -> JResult<JObject> {
    let re = pattern_of(p)?;
    Ok(crate::lambda::lambda(&["java.util.function.Predicate"], move |a| {
        let s = a[0].cast_string()?;
        let t: Vec<char> = s.as_str()?.chars().collect();
        let ok = if whole { re.match_at(&t, 0, true).is_some() } else { re.search(&t, 0).is_some() };
        Ok(crate::box_bool(ok))
    }))
}

/// `matcher.results()`（残りの一致を MatchResult のストリームにする）。
pub fn results(m: &JObject) -> JResult<JObject> {
    let cell = mstate(m)?;
    let mut items = Vec::new();
    loop {
        let snapshot = {
            let mut st = cell.borrow_mut();
            if !find_in(&mut st) {
                break;
            }
            MatcherState {
                re: st.re.clone(),
                text: st.text.clone(),
                utf16: st.utf16.clone(),
                caps: st.caps.clone(),
                first: st.first,
                last: st.last,
                append_pos: 0,
            }
        };
        items.push(alloc(|base| JMatcher { base, st: RefCell::new(snapshot) }));
    }
    Ok(crate::util::stream::from_vec(crate::util::stream::Kind::Obj, items))
}

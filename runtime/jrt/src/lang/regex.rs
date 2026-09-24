//! `String.split` などで使う、ごく小さな正規表現（連結・文字クラス・`\s \d \w`・量指定子 `* + ?`・`|` なし）。

enum Atom {
    Char(char),
    Any,
    Class(Vec<(char, char)>, bool),
}

impl Atom {
    fn matches(&self, c: char) -> bool {
        match self {
            Atom::Char(x) => *x == c,
            Atom::Any => c != '\n',
            Atom::Class(ranges, negated) => ranges.iter().any(|&(a, b)| a <= c && c <= b) != *negated,
        }
    }
}

enum Quant {
    One,
    Star,
    Plus,
    Opt,
}

pub struct SimpleRegex {
    items: Vec<(Atom, Quant)>,
}

fn class_escape(c: char) -> Option<(Vec<(char, char)>, bool)> {
    let ws = vec![(' ', ' '), ('\t', '\r')];
    let digit = vec![('0', '9')];
    let word = vec![('a', 'z'), ('A', 'Z'), ('0', '9'), ('_', '_')];
    Some(match c {
        's' => (ws, false),
        'S' => (ws, true),
        'd' => (digit, false),
        'D' => (digit, true),
        'w' => (word, false),
        'W' => (word, true),
        _ => return None,
    })
}

impl SimpleRegex {
    pub fn parse(re: &str) -> Option<SimpleRegex> {
        let cs: Vec<char> = re.chars().collect();
        let mut items = Vec::new();
        let mut i = 0;
        while i < cs.len() {
            let atom = match cs[i] {
                '\\' => {
                    i += 1;
                    let c = *cs.get(i)?;
                    match class_escape(c) {
                        Some((r, n)) => Atom::Class(r, n),
                        None => Atom::Char(match c {
                            't' => '\t',
                            'n' => '\n',
                            'r' => '\r',
                            other => other,
                        }),
                    }
                }
                '.' => Atom::Any,
                '[' => {
                    i += 1;
                    let mut negated = false;
                    if cs.get(i) == Some(&'^') {
                        negated = true;
                        i += 1;
                    }
                    let mut ranges = Vec::new();
                    while i < cs.len() && cs[i] != ']' {
                        let mut c = cs[i];
                        if c == '\\' {
                            i += 1;
                            let e = *cs.get(i)?;
                            if let Some((r, false)) = class_escape(e) {
                                ranges.extend(r);
                                i += 1;
                                continue;
                            }
                            c = e;
                        }
                        if cs.get(i + 1) == Some(&'-') && cs.get(i + 2).is_some_and(|&x| x != ']') {
                            ranges.push((c, cs[i + 2]));
                            i += 3;
                        } else {
                            ranges.push((c, c));
                            i += 1;
                        }
                    }
                    if i >= cs.len() {
                        return None;
                    }
                    Atom::Class(ranges, negated)
                }
                '(' | ')' | '|' | '{' | '}' | '^' | '$' => return None,
                '*' | '+' | '?' => return None,
                c => Atom::Char(c),
            };
            i += 1;
            let q = match cs.get(i) {
                Some('*') => Quant::Star,
                Some('+') => Quant::Plus,
                Some('?') => Quant::Opt,
                _ => Quant::One,
            };
            if !matches!(q, Quant::One) {
                i += 1;
            }
            items.push((atom, q));
        }
        Some(SimpleRegex { items })
    }

    /// chars[pos..] の先頭で一致する最長の長さ（貪欲、バックトラックあり）。
    fn match_at(&self, chars: &[char], pos: usize, item: usize) -> Option<usize> {
        if item == self.items.len() {
            return Some(pos);
        }
        let (atom, q) = &self.items[item];
        let (min, max) = match q {
            Quant::One => (1, 1),
            Quant::Star => (0, usize::MAX),
            Quant::Plus => (1, usize::MAX),
            Quant::Opt => (0, 1),
        };
        let mut n = 0;
        while n < max && pos + n < chars.len() && atom.matches(chars[pos + n]) {
            n += 1;
        }
        loop {
            if n < min {
                return None;
            }
            if let Some(end) = self.match_at(chars, pos + n, item + 1) {
                return Some(end);
            }
            if n == 0 {
                return None;
            }
            n -= 1;
        }
    }

    pub fn split<'a>(&self, s: &'a str) -> Vec<&'a str> {
        if self.items.is_empty() {
            // split("") は 1 文字ずつに分ける。
            return s.char_indices().map(|(i, c)| &s[i..i + c.len_utf8()]).collect();
        }
        let chars: Vec<char> = s.chars().collect();
        let byte_at: Vec<usize> = s.char_indices().map(|(b, _)| b).chain(std::iter::once(s.len())).collect();
        let mut out = Vec::new();
        let mut start = 0;
        let mut pos = 0;
        while pos < chars.len() {
            match self.match_at(&chars, pos, 0) {
                Some(end) if end > pos => {
                    out.push(&s[byte_at[start]..byte_at[pos]]);
                    start = end;
                    pos = end;
                }
                _ => pos += 1,
            }
        }
        out.push(&s[byte_at[start]..]);
        out
    }
}

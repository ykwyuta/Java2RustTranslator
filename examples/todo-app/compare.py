#!/usr/bin/env python3
"""Spring Boot 版と Rust 版に同じ操作を順に送り、応答（ステータス・リダイレクト先・HTML）を比べる。

使い方: 2 つのアプリを空のデータベースで起動してから
    python3 compare.py http://localhost:8080 http://localhost:8081

HTML は空白の違いだけを吸収して比べる（タグの間の空白を消し、連続する空白を 1 つにする）。
差分がなければ終了コード 0。
"""

import difflib
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from http.cookiejar import CookieJar

SCENARIO = [
    ("GET", "/", None),
    ("GET", "/todos", None),
    ("GET", "/css/app.css", None),
    ("POST", "/todos", {"title": " 牛乳を買う ", "description": "", "dueDate": "2026-10-01"}),
    ("GET", "/todos", None),
    ("GET", "/todos/new", None),
    ("POST", "/todos", {"title": " ", "description": "x" * 1001, "dueDate": "not-a-date"}),
    ("POST", "/todos", {"title": "掃除", "description": "床と\n窓 <b>&amp;</b>", "dueDate": ""}),
    ("POST", "/todos", {"title": "期限切れのもの", "description": "", "dueDate": "2020-01-01"}),
    ("POST", "/todos/1/toggle", {"filter": "active"}),
    ("GET", "/todos?filter=active", None),
    ("GET", "/todos?filter=completed&q=", None),
    ("GET", "/todos?filter=ALL&q=%E6%8E%83", None),
    ("GET", "/todos/2/edit", None),
    ("POST", "/todos/2", {"title": "", "description": "", "dueDate": "", "done": "true"}),
    ("POST", "/todos/2", {"title": "掃除する", "description": "", "dueDate": "2026-12-31", "done": "true"}),
    ("POST", "/todos/3", {"title": "あ" * 101, "description": "", "dueDate": "2026-02-30", "_done": "on"}),
    ("POST", "/todos/3", {"title": "期限切れのもの", "description": "  ", "dueDate": "2020-01-01", "_done": "on"}),
    ("GET", "/todos/3/edit", None),
    ("GET", "/todos", None),
    ("GET", "/todos/2/edit", None),
    ("POST", "/todos/999/delete", None),
    ("GET", "/todos/999/edit", None),
    ("GET", "/no-such-page", None),
    ("POST", "/todos/completed/delete", None),
    ("GET", "/todos", None),
    ("POST", "/todos/3/delete", None),
    ("GET", "/todos", None),
]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


# テンプレートエンジンごとの文字参照の書き方の違い（Thymeleaf は &lt;、askama は &#60;）
ENTITIES = {"&#60;": "&lt;", "&#62;": "&gt;", "&#38;": "&amp;", "&#34;": "&quot;", "&#39;": "&#39;"}


def normalize(html):
    for numeric, named in ENTITIES.items():
        html = html.replace(numeric, named)
    html = re.sub(r">\s+<", "><", html)
    html = re.sub(r"\s+", " ", html)
    return html.replace("><", ">\n<").strip()


def run(base):
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()), NoRedirect())
    transcript = []
    for method, path, form in SCENARIO:
        data = urllib.parse.urlencode(form or {}).encode() if method == "POST" else None
        request = urllib.request.Request(base + path, data=data, method=method, headers={"Accept": "text/html"})
        try:
            response = opener.open(request)
        except urllib.error.HTTPError as e:
            response = e
        location = response.headers.get("Location") or ""
        location = location.removeprefix(base)
        body = response.read().decode("utf-8")
        transcript.append(f"### {method} {path} -> {response.status} {location}")
        if response.status not in (301, 302, 303) and "text/html" in (response.headers.get("Content-Type") or ""):
            transcript.extend(normalize(body).splitlines())
    return transcript


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    left, right = sys.argv[1], sys.argv[2]
    expected, actual = run(left), run(right)
    diff = list(difflib.unified_diff(expected, actual, left, right, lineterm=""))
    if diff:
        print("\n".join(diff))
        return 1
    print(f"no differences in {len(SCENARIO)} requests ({len(expected)} lines)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

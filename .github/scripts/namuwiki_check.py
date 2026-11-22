"""
Whether namu.wiki can be read for a song's background: robots.txt, the
article pages for real songs under the names they are filed by, and the
search page. Prints status codes and the first part of the article text -
never lyrics, and stops at a few hundred characters. No API key, no cost.
"""
import html
import os
import re
import urllib.parse
import urllib.request

REPORT = os.environ.get("LIVE_LYRICS_REPORT", "namu.md")
UA = "Mozilla/5.0 (Linux; Android 16; SM-S938N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
PAGES = [
    "アイドル(YOASOBI)", "아이돌(YOASOBI)", "春を告げる", "Machine Love", "テトリス(柊マグネタイト)",
    "いますぐ輪廻", "砂の惑星", "ヴァンパイア(DECO*27)", "KICK BACK", "HOPE(NF)",
    "World's Smallest Violin", "You're Welcome", "밤양갱", "Supernova(aespa)",
]


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ko-KR,ko;q=0.9"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, (error.read() or b"").decode("utf-8", "replace")
    except Exception as error:  # noqa: BLE001
        return 0, type(error).__name__ + ": " + str(error)[:80]


def text_of(page):
    page = re.sub(r"(?s)<(script|style)[^>]*>.*?</\1>", " ", page)
    return " ".join(html.unescape(re.sub(r"<[^>]+>", " ", page)).split())


def main():
    out = ["# namu.wiki check\n"]
    code, body = fetch("https://namu.wiki/robots.txt")
    out.append(f"## robots.txt (HTTP {code})\n\n```\n{body[:800]}\n```\n")
    out.append("## Articles\n")
    out.append("| page | HTTP | bytes | first text |\n|---|---|---|---|")
    for name in PAGES:
        url = "https://namu.wiki/w/" + urllib.parse.quote(name)
        code, body = fetch(url)
        text = text_of(body)
        # Past the site's header, where an article's own text begins.
        start = max(text.find("분류"), 0)
        out.append(f"| {name} | {code} | {len(body)} | {text[start:start + 260].replace('|', '/')} |")
    code, body = fetch("https://namu.wiki/Search?q=" + urllib.parse.quote("春を告げる yama"))
    out.append(f"\n## Search page (HTTP {code}, {len(body)} bytes)\n\n{text_of(body)[:400]}\n")
    report = "\n".join(out)
    open(REPORT, "w", encoding="utf-8").write(report)
    print(report)


if __name__ == "__main__":
    main()

"""
Free places a song's background could come from, tried on real songs:
VocaDB (vocal-synth songs), Wikipedia (ja/en/ko), Genius' "About", and
AnimeThemes (anime tie-ins). Prints whether each found this song and the
first part of what it says - never lyrics. No API key, no cost.
"""
import json
import os
import urllib.parse
import urllib.request

REPORT = os.environ.get("LIVE_LYRICS_REPORT", "sources.md")
UA = {"User-Agent": "BitChordKO-background-check/1.0 (personal project)"}
SONGS = [
    ("アイドル", "YOASOBI"),
    ("春を告げる", "yama"),
    ("Machine Love", "Jamie Paige"),
    ("テトリス", "柊マグネタイト"),
    ("いますぐ輪廻", "NAKISO"),
    ("砂の惑星", "ハチ"),
    ("ヴァンパイア", "DECO*27"),
    ("KICK BACK", "米津玄師"),
    ("HOPE", "NF"),
    ("World's Smallest Violin", "AJR"),
    ("You're Welcome", "Dwayne Johnson"),
    ("밤양갱", "비비"),
    ("Supernova", "aespa"),
]


def get(url):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20) as r:
            return json.loads(r.read())
    except Exception as e:  # noqa: BLE001
        return {"__error": type(e).__name__ + ": " + str(e)[:80]}


def short(text, n=220):
    return " ".join((text or "").split())[:n].replace("|", "/")


def vocadb(title, artist):
    q = urllib.parse.quote(title)
    data = get(f"https://vocadb.net/api/songs?query={q}&maxResults=5&fields=Tags,WebLinks&lang=Default&nameMatchMode=Auto")
    items = data.get("items") or []
    if not items:
        return "none", ""
    hit = items[0]
    details = get(f"https://vocadb.net/api/songs/{hit['id']}?fields=Tags,WebLinks,Notes&lang=Default")
    notes = (details.get("notes") or {})
    text = notes.get("english") or notes.get("original") or ""
    tags = ", ".join(t["tag"]["name"] for t in (details.get("tags") or [])[:6])
    return f"{hit.get('name')} — {hit.get('artistString')}", f"notes: {short(text) or '(none)'} · tags: {tags}"


def wikipedia(lang, title, artist):
    q = urllib.parse.quote(f"{title} {artist}")
    data = get(f"https://{lang}.wikipedia.org/w/api.php?action=query&list=search&srsearch={q}&srlimit=1&format=json")
    hits = (data.get("query") or {}).get("search") or []
    if not hits:
        return "none", ""
    page = hits[0]["title"]
    summary = get(f"https://{lang}.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(page.replace(' ', '_'))}")
    return page, short(summary.get("extract"))


def genius(title, artist):
    q = urllib.parse.quote(f"{title} {artist}")
    data = get(f"https://genius.com/api/search/multi?q={q}")
    sections = ((data.get("response") or {}).get("sections")) or []
    song = None
    for s in sections:
        if s.get("type") in ("song", "top_hit"):
            for h in s.get("hits") or []:
                if h.get("type") == "song":
                    song = h["result"]
                    break
        if song:
            break
    if not song:
        return "none", data.get("__error", "")
    details = get(f"https://genius.com/api/songs/{song['id']}?text_format=plain")
    desc = (((details.get("response") or {}).get("song") or {}).get("description") or {}).get("plain", "")
    return f"{song.get('title')} — {song.get('primary_artist', {}).get('name')}", short(desc) or details.get("__error", "(no About)")


def animethemes(title, artist):
    q = urllib.parse.quote(title)
    data = get(f"https://api.animethemes.moe/animetheme?filter[song][title]={q}&include=anime,song.artists&page[size]=3")
    themes = data.get("animethemes") or []
    if not themes:
        return "none", data.get("__error", "")
    t = themes[0]
    artists = ", ".join(a.get("name", "") for a in ((t.get("song") or {}).get("artists") or []))
    return f"{(t.get('anime') or {}).get('name')} {t.get('slug')}", f"song: {(t.get('song') or {}).get('title')} by {artists}"


def main():
    out = ["# Free background sources\n"]
    found = {k: 0 for k in ("VocaDB", "Wikipedia ja", "Wikipedia en", "Wikipedia ko", "Genius", "AnimeThemes")}
    for title, artist in SONGS:
        out.append(f"## {title} — {artist}\n")
        checks = [
            ("VocaDB", lambda: vocadb(title, artist)),
            ("Wikipedia ja", lambda: wikipedia("ja", title, artist)),
            ("Wikipedia en", lambda: wikipedia("en", title, artist)),
            ("Wikipedia ko", lambda: wikipedia("ko", title, artist)),
            ("Genius", lambda: genius(title, artist)),
            ("AnimeThemes", lambda: animethemes(title, artist)),
        ]
        for name, fn in checks:
            hit, text = fn()
            if hit != "none":
                found[name] += 1
            out.append(f"- **{name}**: {short(hit, 80)} · {text}")
        out.append("")
    out.append("## Hits (a hit is not always the right song: read the rows)\n")
    out += [f"- {k}: {v}/{len(SONGS)}" for k, v in found.items()]
    report = "\n".join(out)
    open(REPORT, "w", encoding="utf-8").write(report)
    print(report)


if __name__ == "__main__":
    main()

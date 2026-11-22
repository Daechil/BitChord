"""
Can Claude's web fetch tool read a namu.wiki song page, and does the page it
gets carry the lyrics with a Korean translation line by line? For BitChord
KO's "use namu.wiki's translation" idea. Prints only what was reached, error
codes, sizes and counts the model gives - never the lyrics, never a
translation, never the key: this workflow's log is public. Run by hand from
the "Lyrics match check" workflow with claude_search set to "fetch".
"""
import json
import os
import time
import urllib.error
import urllib.request

KEY = os.environ.get("ANTHROPIC_API_KEY", "").strip()
REPORT = os.environ.get("LIVE_LYRICS_REPORT", "namu-fetch.md")
MODEL = "claude-haiku-5-5"
PRICE_IN, PRICE_OUT = 0.10, 0.50
SEARCH_USD = 0.01
SONGS = [("抜錨", "ナナホシ管弦楽団")]

SYSTEM = (
    "You check a namu.wiki article for a music app. Find the namu.wiki article for this exact song "
    "(search namu.wiki, then fetch the article page itself), and report what the page holds. "
    "Never copy any lyric or any translated line into your answer: give only counts and short descriptions in Korean."
)

SCHEMA = {
    "type": "object",
    "properties": {
        "article_url": {"type": "string"},
        "fetched": {"type": "boolean"},
        "fetch_problem": {"type": "string"},
        "has_lyrics": {"type": "boolean"},
        "has_korean_translation": {"type": "boolean"},
        "original_lines": {"type": "integer"},
        "translated_lines": {"type": "integer"},
        "has_pronunciation": {"type": "boolean"},
        "layout": {"type": "string"},
        "complete": {"type": "string"},
    },
    "required": ["article_url", "fetched", "fetch_problem", "has_lyrics", "has_korean_translation",
                 "original_lines", "translated_lines", "has_pronunciation", "layout", "complete"],
    "additionalProperties": False,
}


def call(body):
    request = urllib.request.Request(
        "https://api.anthropic.com/v1/messages",
        data=json.dumps(body).encode(),
        headers={"x-api-key": KEY, "anthropic-version": "2023-06-01", "content-type": "application/json"},
    )
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            return response.status, json.loads(response.read()), time.time() - started
    except urllib.error.HTTPError as error:
        raw = error.read() or b"{}"
        try:
            data = json.loads(raw)
        except ValueError:
            data = {"error": {"message": raw[:200].decode(errors="replace")}}
        return error.code, data, time.time() - started


def tools(fetch_type):
    return [
        {"type": "web_search_20260209", "name": "web_search", "max_uses": 2,
         "allowed_callers": ["direct"], "allowed_domains": ["namu.wiki"]},
        {"type": fetch_type, "name": "web_fetch", "max_uses": 2, "allowed_callers": ["direct"],
         "allowed_domains": ["namu.wiki"], "max_content_tokens": 40000},
    ]


def blocks_summary(data):
    """What came back, block by block: kinds, URLs, error codes and sizes, never the text."""
    lines = []
    for block in data.get("content", []) or []:
        kind = block.get("type")
        if kind == "server_tool_use":
            url = (block.get("input") or {}).get("url") or (block.get("input") or {}).get("query")
            lines.append(f"- {block.get('name')} asked: `{str(url)[:120]}`")
        elif kind == "web_fetch_tool_result":
            content = block.get("content") or {}
            if content.get("type", "").endswith("error"):
                lines.append(f"- fetch result: error `{content.get('error_code')}`")
            else:
                doc = content.get("content") or {}
                source = doc.get("source") or {}
                text = source.get("data") or ""
                lines.append(f"- fetch result: `{content.get('url', '')[:120]}` · {source.get('media_type')} · {len(text)} chars"
                             f" · Hangul {sum(1 for c in text if '가' <= c <= '힣')} · kana {sum(1 for c in text if '぀' <= c <= 'ヿ')}")
        elif kind == "web_search_tool_result":
            content = block.get("content")
            if isinstance(content, dict):
                lines.append(f"- search result: error `{content.get('error_code')}`")
            else:
                lines.append(f"- search result: {len(content or [])} pages: " + ", ".join(
                    f"`{(r.get('url') or '')[:80]}`" for r in (content or [])[:5]))
    return lines


def main():
    out = ["# namu.wiki fetch check", ""]
    if not KEY:
        out.append("No ANTHROPIC_API_KEY secret.")
    total = 0.0
    for title, artist in SONGS:
        for fetch_type in ("web_fetch_20260209", "web_fetch_20250910"):
            body = {
                "model": MODEL,
                "max_tokens": 4000,
                "system": SYSTEM,
                "tools": tools(fetch_type),
                "messages": [{"role": "user", "content": json.dumps({"title": title, "artist": artist}, ensure_ascii=False)}],
                "output_config": {"effort": "medium", "format": {"type": "json_schema", "schema": SCHEMA}},
            }
            code, data, seconds = call(body)
            usage = data.get("usage", {}) or {}
            server = usage.get("server_tool_use") or {}
            tin, tout = usage.get("input_tokens", 0) or 0, usage.get("output_tokens", 0) or 0
            usd = tin * PRICE_IN / 1e6 + tout * PRICE_OUT / 1e6 + (server.get("web_search_requests") or 0) * SEARCH_USD
            total += usd
            out.append(f"## {title} — {artist} · {fetch_type}")
            out.append("")
            out.append(f"- HTTP {code} · {data.get('stop_reason')} · {seconds:.1f}s · in {tin}/out {tout} · "
                       f"searches {server.get('web_search_requests')} · fetches {server.get('web_fetch_requests')} · ${usd:.4f} (~{usd * 1400:.0f}원)")
            if code != 200:
                out.append(f"- error: {((data.get('error') or {}).get('type', ''))}: {((data.get('error') or {}).get('message', ''))[:300]}")
                out.append("")
                continue
            out.extend(blocks_summary(data))
            text = "".join(b.get("text", "") for b in data.get("content", []) if b.get("type") == "text").strip()
            texts = [b.get("text", "") for b in data.get("content", []) if b.get("type") == "text"]
            try:
                answer = json.loads(text)
            except ValueError:
                try:
                    answer = json.loads(texts[-1]) if texts else {}
                except ValueError:
                    answer = {}
            out.append("")
            out.append("model's report:")
            for key in SCHEMA["properties"]:
                out.append(f"- {key}: {str(answer.get(key))[:200]}")
            out.append("")
            break  # the newer tool worked: no need for the older one
    out.append(f"Total about ${total:.4f} (~{total * 1400:.0f}원)")
    report = "\n".join(out) + "\n"
    with open(REPORT, "w", encoding="utf-8") as handle:
        handle.write(report)
    print(report)


if __name__ == "__main__":
    main()

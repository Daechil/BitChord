"""
Tries Claude's web search tool for BitChord KO's "song background" idea:
what one search costs in tokens and money, how long it takes, whether the
summary is any good, and whether web search works alongside the JSON answer
format the app uses. Prints usage numbers and the short background summaries
only - never the key, never lyrics. Run by hand from the "Lyrics match check"
workflow with the claude_search input; needs the ANTHROPIC_API_KEY secret.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

KEY = os.environ.get("ANTHROPIC_API_KEY", "").strip()
REPORT = os.environ.get("LIVE_LYRICS_REPORT", "claude-search.md")
PRICE = {  # $ per million tokens (input, output), from the pricing page
    "claude-haiku-5-5": (0.10, 0.50),
    "claude-sonnet-5-5": (2.0, 10.0),
}
SEARCH_USD = 0.01  # $10 per 1,000 searches

SYSTEM = (
    "You research a song's background for a lyrics translator in a music app. "
    "Search the web for this exact song (title and artist), then write 3 to 5 short sentences in Korean: "
    "what the song is about, what it was written for (anime, drama, game, film) if anything, "
    "and anything the artist or official sources said about its meaning. "
    "Never quote the lyrics. If you cannot find reliable information about this exact song, answer only: 정보 없음"
)


def call(body):
    request = urllib.request.Request(
        "https://api.anthropic.com/v1/messages",
        data=json.dumps(body).encode(),
        headers={"x-api-key": KEY, "anthropic-version": "2023-06-01", "content-type": "application/json"},
    )
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=240) as response:
            return response.status, json.loads(response.read()), time.time() - started
    except urllib.error.HTTPError as error:
        raw = error.read() or b"{}"
        try:
            data = json.loads(raw)
        except ValueError:
            data = {"error": {"message": raw[:200].decode(errors="replace")}}
        return error.code, data, time.time() - started


def text_of(data):
    return "".join(b.get("text", "") for b in data.get("content", []) if b.get("type") == "text").strip()


def row(label, model, tool, code, data, seconds):
    usage = data.get("usage", {}) or {}
    tin = usage.get("input_tokens", 0) or 0
    tout = usage.get("output_tokens", 0) or 0
    searches = ((usage.get("server_tool_use") or {}).get("web_search_requests")) or 0
    pin, pout = PRICE.get(model, (0, 0))
    usd = tin * pin / 1e6 + tout * pout / 1e6 + searches * SEARCH_USD
    error = ""
    if code != 200:
        error = ((data.get("error") or {}).get("type", "") + ": " + (data.get("error") or {}).get("message", ""))[:200]
    return (
        f"| {label} | {model} | {tool} | HTTP {code} | {data.get('stop_reason', '-')} | {searches} | {tin} | {tout} | "
        f"{seconds:.1f}s | ${usd:.4f} (~{usd * 1400:.0f}원) | {error.replace('|', '/')} |",
        usd,
    )


FORCE = (
    " Always run exactly one web search for the title and artist before you answer, even if you think you know the song."
)


def research(model, tool, title, artist, json_format=False, max_uses=1, domains=None, force=False):
    tool_def = {"type": tool, "name": "web_search", "max_uses": max_uses}
    if domains:
        tool_def["allowed_domains"] = domains
    body = {
        "model": model,
        "max_tokens": 2500,
        "system": SYSTEM + (FORCE if force else ""),
        "messages": [{"role": "user", "content": json.dumps({"title": title, "artist": artist}, ensure_ascii=False)}],
        "tools": [tool_def],
    }
    if json_format:
        body["output_config"] = {
            "format": {
                "type": "json_schema",
                "schema": {
                    "type": "object",
                    "properties": {"background": {"type": "string"}},
                    "required": ["background"],
                    "additionalProperties": False,
                },
            }
        }
    return call(body)


def main():
    if not KEY:
        print("No ANTHROPIC_API_KEY secret is set for this repository.")
        sys.exit(1)
    rows, notes, total = [], [], 0.0
    namu = ["namu.wiki"]
    if os.environ.get("CLAUDE_SEARCH_CASES") == "namu":
        cases = [
            ("N1 namu.wiki only", "claude-haiku-5-5", "春を告げる", "yama", True, namu, True),
            ("N2 namu.wiki only", "claude-haiku-5-5", "テトリス", "柊マグネタイト", True, namu, True),
            ("N3 namu.wiki only", "claude-haiku-5-5", "Machine Love", "Jamie Paige", True, namu, True),
            ("N4 any site, forced", "claude-haiku-5-5", "春を告げる", "yama", True, None, True),
        ]
    else:
        cases = [
            ("1 Haiku, 1 search", "claude-haiku-5-5", "アイドル", "YOASOBI", False, None, False),
            ("2 Haiku, 1 search", "claude-haiku-5-5", "春を告げる", "yama", False, None, False),
            ("3 Sonnet, 1 search", "claude-sonnet-5-5", "春を告げる", "yama", False, None, False),
            ("4 Haiku, lesser-known", "claude-haiku-5-5", "Machine Love", "Jamie Paige", False, None, False),
            ("5 Haiku + JSON format", "claude-haiku-5-5", "アイドル", "YOASOBI", True, None, False),
        ]
    for label, model, title, artist, as_json, domains, force in cases:
        tool = "web_search_20260209"
        code, data, seconds = research(model, tool, title, artist, as_json, domains=domains, force=force)
        if code == 400 and "web_search" in json.dumps(data.get("error", {})):
            # The newer tool may not be offered for this model: the basic one instead.
            line, usd = row(label + " (newer tool refused)", model, tool, code, data, seconds)
            rows.append(line)
            total += usd
            tool = "web_search_20250305"
            code, data, seconds = research(model, tool, title, artist, as_json, domains=domains, force=force)
        line, usd = row(label, model, tool, code, data, seconds)
        rows.append(line)
        total += usd
        if code == 200:
            urls = []
            for block in data.get("content", []):
                if block.get("type") == "web_search_tool_result":
                    content = block.get("content")
                    if isinstance(content, list):
                        urls += [r.get("url", "") for r in content if isinstance(r, dict)]
                    elif isinstance(content, dict):
                        urls.append("error: " + str(content.get("error_code", "")))
            shown = ", ".join(u.split("/")[2] if "//" in u else u for u in urls[:8]) or "(none)"
            notes.append(f"**{label} · {title} — {artist}**\n\nresult sites: {shown}\n\n{text_of(data)[:1200]}\n")
        time.sleep(2)
    out = [
        "# Claude web search check\n",
        "| case | model | tool | status | stop | searches | input tok | output tok | time | cost | error |",
        "|---|---|---|---|---|---|---|---|---|---|---|",
        *rows,
        f"\nTotal: ${total:.4f} (~{total * 1400:.0f}원)\n",
        "## Background summaries\n",
        *notes,
    ]
    report = "\n".join(out)
    with open(REPORT, "w", encoding="utf-8") as handle:
        handle.write(report)
    print(report)


if __name__ == "__main__":
    main()

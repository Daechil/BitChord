#!/usr/bin/env python3
"""Release notes for the Korean builds, taken from KO_CHANGELOG.md.

  build  writes the notes for the release a build is about to publish
  sync   rewrites the notes of releases that already exist

A section of the changelog starts at "## <release tag>" and runs to the next
one. Both commands put the same footer under it: version, build, commit and
which file to install.
"""
import argparse
import json
import os
import re
import subprocess
import sys

CHANGELOG = "KO_CHANGELOG.md"


def sections(path=CHANGELOG):
    """Tag -> the text under its heading."""
    found, tag, lines = {}, None, []
    with open(path, encoding="utf-8") as changelog:
        for line in changelog:
            heading = re.match(r"^##\s+(\S+)", line)
            if heading:
                if tag:
                    found[tag] = "\n".join(lines).strip()
                tag, lines = heading.group(1), []
            elif tag is not None:
                lines.append(line.rstrip("\n"))
    if tag:
        found[tag] = "\n".join(lines).strip()
    return found


def released(tag, repo=()):
    return gh("release", "view", tag, *repo, "--json", "tagName").returncode == 0


def sections_only(notes):
    """An entry from its first "### " on: its banner and one-line lead left out."""
    lines = notes.splitlines()
    start = next((i for i, line in enumerate(lines) if line.startswith("### ")), None)
    return "\n".join(lines[start:]).strip() if start is not None else notes.strip()


def with_unreleased(tag, notes, repo=()):
    """[notes] and, after them, the entries just below [tag] that never became a
    release (a build cancelled by the next one): their changes ship in this one,
    so its notes say so, each section headed with its own version. A stable
    release folds only stable entries: its own entry sums up the betas."""
    tags = list(sections().keys())  # newest first, as the changelog is written
    if tag not in tags:
        return notes
    folded = []
    stable = not tag.endswith("-beta")
    for older in tags[tags.index(tag) + 1:]:
        # A stable release's own entry already sums up the betas before it.
        if stable and older.endswith("-beta"):
            continue
        if released(older, repo):
            break
        entry = sections_only(sections()[older])
        short = re.sub(r"^v[\d.]+-", "", older)
        entry = re.sub(r"^### ", "### (%s) " % short, entry, flags=re.M)
        folded.append(entry)
    if not folded:
        return notes
    return notes.strip() + "\n\n" + "\n\n".join(folded)


def body(tag, notes, commit, apk):
    version = tag[1:] if tag.startswith("v") else tag
    build = re.search(r"ko[.-](\d+)(?:-beta)?$", tag)
    facts = ["버전 " + version]
    if build:
        facts.append("빌드 " + build.group(1))
    if tag.endswith("-beta"):
        # Said first, above the notes too: a beta is offered only to those who asked.
        facts.append("베타(시험판)")
    if commit and re.fullmatch(r"[0-9a-f]{7,40}", commit):
        facts.append("커밋 " + commit[:7])
    install = (
        "설치: 휴대폰에서 아래 **Assets**의 `%s`를 받아서 열어 주세요." % apk
        if apk
        else "설치: 휴대폰에서 아래 **Assets**의 APK를 받아서 열어 주세요."
    )
    beta = (
        "> 🧪 **베타 버전이에요.** 앱 설정 › 기타 › **베타 버전 받기**를 켠 사람에게만 업데이트로 떠요. 문제가 있을 수 있어요.\n\n"
        if tag.endswith("-beta")
        else ""
    )
    if beta:
        # The banner above says it already; the entry's own "베타예요." line said it twice.
        notes = "\n".join(line for line in notes.splitlines() if not line.startswith("> 베타예요"))
    # Kinds stay plain text ("[새 기능] 제목"): the app shows release bodies as they are, and an
    # older app showed GitHub's colour markup ($\\color{..}) as code. The app colours them itself.
    return "%s## 바뀐 내용\n\n%s\n\n---\n\n%s\n\n%s\n" % (beta, notes.strip(), " · ".join(facts), install)


def gh(*args):
    return subprocess.run(["gh", *args], capture_output=True, text=True)


def build(args):
    notes = sections().get(args.tag)
    if not notes:
        # No entry was written for this build: say what the commit says,
        # rather than publish a release with nothing under it.
        subject = subprocess.run(
            ["git", "log", "-1", "--format=%s"], capture_output=True, text=True
        ).stdout.strip()
        notes = "- %s\n\n이 빌드의 설명은 아직 정리되지 않았어요. 위는 커밋 제목이에요." % (subject or "변경 사항")
        print("::warning::KO_CHANGELOG.md has no entry for %s" % args.tag)
    else:
        notes = with_unreleased(args.tag, notes)
    with open(args.out, "w", encoding="utf-8") as out:
        out.write(body(args.tag, notes, args.commit, args.apk))
    print("wrote notes for %s to %s" % (args.tag, args.out))


def sync(args):
    repo = ["--repo", args.repo] if args.repo else []
    failed = False
    for tag, notes in sections().items():
        seen = gh("release", "view", tag, *repo, "--json", "body,assets,targetCommitish")
        if seen.returncode != 0:
            print("%s: no such release, skipped" % tag)
            continue
        release = json.loads(seen.stdout)
        apk = next((a["name"] for a in release.get("assets", []) if a["name"].endswith(".apk")), None)
        wanted = body(tag, with_unreleased(tag, notes, repo), release.get("targetCommitish"), apk)
        if (release.get("body") or "").strip() == wanted.strip():
            print("%s: already up to date" % tag)
            continue
        path = os.path.join(args.tmp, "notes-%s.md" % tag)
        with open(path, "w", encoding="utf-8") as out:
            out.write(wanted)
        edited = gh("release", "edit", tag, *repo, "--notes-file", path)
        if edited.returncode != 0:
            failed = True
            print("::error::%s: %s" % (tag, edited.stderr.strip()))
        else:
            print("%s: updated" % tag)
    if failed:
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    one = commands.add_parser("build")
    one.add_argument("--tag", required=True)
    one.add_argument("--commit", default="")
    one.add_argument("--apk", default="")
    one.add_argument("--out", required=True)
    one.set_defaults(run=build)
    every = commands.add_parser("sync")
    every.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    every.add_argument("--tmp", default=os.environ.get("RUNNER_TEMP", "."))
    every.set_defaults(run=sync)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()

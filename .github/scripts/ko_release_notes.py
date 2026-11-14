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


def body(tag, notes, commit, apk):
    version = tag[1:] if tag.startswith("v") else tag
    build = re.search(r"ko[.-](\d+)$", tag)
    facts = ["버전 " + version]
    if build:
        facts.append("빌드 " + build.group(1))
    if commit and re.fullmatch(r"[0-9a-f]{7,40}", commit):
        facts.append("커밋 " + commit[:7])
    install = (
        "설치: 휴대폰에서 아래 **Assets**의 `%s`를 받아서 열어 주세요." % apk
        if apk
        else "설치: 휴대폰에서 아래 **Assets**의 APK를 받아서 열어 주세요."
    )
    return "## 바뀐 내용\n\n%s\n\n---\n\n%s\n\n%s\n" % (notes.strip(), " · ".join(facts), install)


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
        wanted = body(tag, notes, release.get("targetCommitish"), apk)
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

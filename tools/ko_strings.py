#!/usr/bin/env python3
"""KO strings: the strings this fork adds live in strings_ko.xml (English in
values/, Korean in values-ko/), apart from upstream's strings.xml.

  add FILE.json          append [[name, english, korean], ...] to both strings_ko.xml
  check-upstream REF     before merging upstream REF: its strings that would
                         collide with a KO string's name (rename the KO one first)

Run from the repository root.
"""
import json
import re
import subprocess
import sys
import xml.dom.minidom

RES = "app/src/main/res"
NAME = re.compile(r'<(?:string|plurals|string-array)\s+name="([^"]+)"')


def names(text):
    return set(NAME.findall(text))


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def add(path):
    entries = json.load(open(path, encoding="utf-8"))
    for loc, idx in (("values", 1), ("values-ko", 2)):
        ko = f"{RES}/{loc}/strings_ko.xml"
        text = read(ko)
        taken = names(text) | names(read(f"{RES}/{loc}/strings.xml"))
        dup = [e[0] for e in entries if e[0] in taken]
        if dup:
            sys.exit(f"already defined in {loc}: {dup}")
        block = "".join(f'    <string name="{e[0]}">{e[idx]}</string>\n' for e in entries)
        i = text.rindex("</resources>")
        with open(ko, "w", encoding="utf-8") as f:
            f.write(text[:i] + block + text[i:])
        xml.dom.minidom.parse(ko)
    print(f"added {len(entries)} to strings_ko.xml (values, values-ko)")


def check_upstream(ref):
    shown = subprocess.run(
        ["git", "show", f"{ref}:{RES}/values/strings.xml"], capture_output=True, text=True
    )
    if shown.returncode != 0:
        sys.exit(shown.stderr.strip())
    clash = sorted(names(shown.stdout) & names(read(f"{RES}/values/strings_ko.xml")))
    if clash:
        print("upstream now defines these KO string names; rename the KO ones before merging:")
        print("\n".join("  " + n for n in clash))
        sys.exit(1)
    print(f"no clashes between {ref} and strings_ko.xml")


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ("add", "check-upstream"):
        sys.exit(__doc__)
    (add if sys.argv[1] == "add" else check_upstream)(sys.argv[2])


if __name__ == "__main__":
    main()

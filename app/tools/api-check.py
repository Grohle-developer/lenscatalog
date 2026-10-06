#!/usr/bin/env python3
"""Fail the build when classes.dex calls anything newer than the camera's Android.

The camera runs Android 2.3.7 (API 10), but the app compiles against a newer
android.jar and Java 8's class library, so javac accepts calls the camera does
not have (String.join, Objects.equals, View.setAlpha...). They only fail at
run time, on the camera, as NoSuchMethodError. This reads every class, method
and field the dex refers to (dexdump -d), resolves each through the class
hierarchy — the app's own classes from the dex, the platform's from the SDK's
api-versions.xml — and lists those added after MIN_API. It runs on the dex
rather than javac's classes, so what d8 desugars or backports for --min-api 10
(lambdas, try-with-resources, Objects.requireNonNull) is not reported.

    tools/api-check.py <dexdump> <api-versions.xml> <classes.dex> [min_api]
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

MIN_API_DEFAULT = 10

# Calls made only behind an android.os.Build.VERSION.SDK_INT check: none today.
# Format: "java/lang/String.isEmpty()Z" or "android/os/Build.SERIAL".
ALLOWED = set()


def load_api(path, min_api):
    """Every platform class: (since, members, the supertypes it had at min_api).

    The hierarchy is the one min_api had, not today's: Method extends
    Executable only since API 26, and on API 10 its getName() comes from Member.
    """
    classes = {}
    for c in ET.parse(path).getroot().iter("class"):
        since = int(c.get("since", "1"))
        members, supers = {}, []
        for e in c:
            if e.tag in ("extends", "implements"):
                if int(e.get("since", "1")) <= min_api and int(e.get("removed", "10000")) > min_api:
                    supers.append(e.get("name"))
            elif e.tag in ("method", "field"):
                members[e.get("name")] = int(e.get("since", str(since)))
        classes[c.get("name")] = (since, members, supers)
    return classes


def load_dex(dump):
    """The app's own classes: name -> (declared members, supertypes)."""
    own = {}
    cur = None
    section = None
    pending_name = None
    for line in dump.splitlines():
        m = re.match(r"\s+Class descriptor\s+:\s+'L([\w/$]+);'", line)
        if m:
            cur = m.group(1)
            own[cur] = (set(), [])
            section = None
            continue
        if cur is None:
            continue
        m = re.match(r"\s+Superclass\s+:\s+'L([\w/$]+);'", line)
        if m:
            own[cur][1].append(m.group(1))
            continue
        m = re.match(r"\s+(Interfaces|Static fields|Instance fields|Direct methods|Virtual methods)\s+-", line)
        if m:
            section = m.group(1)
            continue
        if section == "Interfaces":
            m = re.match(r"\s+#\d+\s+:\s+'L([\w/$]+);'", line)
            if m:
                own[cur][1].append(m.group(1))
                continue
        m = re.match(r"\s+name\s+:\s+'([^']+)'", line)
        if m:
            pending_name = m.group(1)
            continue
        m = re.match(r"\s+type\s+:\s+'([^']+)'", line)
        if m and pending_name is not None:
            if section in ("Direct methods", "Virtual methods"):
                own[cur][0].add(pending_name + m.group(1))
            elif section in ("Static fields", "Instance fields"):
                own[cur][0].add(pending_name)
            pending_name = None
    return own


def member_since(api, own, cls, member, seen):
    """API level at which `member`, reached through `cls`, exists: 0 when the app
    declares it, None when nothing on the path knows it."""
    if cls in seen:
        return None
    seen.add(cls)
    if cls in own:
        members, supers = own[cls]
        if member in members:
            return 0
        since = 0
    elif cls in api:
        since, members, supers = api[cls]
        if member in members:
            return max(since, members[member])
    else:
        return None
    best = None
    for s in supers:
        v = member_since(api, own, s, member, set(seen))
        if v is not None and (best is None or v < best):
            best = v
    return None if best is None else max(since, best)


def platform_root(api, own, cls):
    """Whether cls is, or descends from, a platform class (else it is all the app's)."""
    seen = set()
    stack = [cls]
    while stack:
        c = stack.pop()
        if c in seen:
            continue
        seen.add(c)
        if c in api and c != "java/lang/Object":
            return True
        if c in own:
            stack.extend(own[c][1])
    return False


REF = re.compile(r"L([\w/$]+);\.([\w<>$-]+):(\S+)")
TYPE = re.compile(r"(?:const-class|new-instance|check-cast|instance-of|new-array|filled-new-array)\b.*?, \[*L([\w/$]+);")


def main():
    if len(sys.argv) < 4:
        print(__doc__, file=sys.stderr)
        return 2
    dexdump, api_xml, dex = sys.argv[1:4]
    min_api = int(sys.argv[4]) if len(sys.argv) > 4 else MIN_API_DEFAULT
    api = load_api(api_xml, min_api)
    dump = subprocess.run([dexdump, "-d", dex], check=True, capture_output=True,
                          text=True, errors="replace").stdout
    own = load_dex(dump)

    problems = {}

    def report(what, where):
        problems.setdefault(what, set()).add(where)

    def check_class(cls, where):
        if cls in api and api[cls][0] > min_api:
            report("%s (class, API %d)" % (cls, api[cls][0]), where)

    for cls, (_, supers) in own.items():
        for s in supers:
            check_class(s, cls)

    current = "?"
    for line in dump.splitlines():
        m = re.match(r"\s+Class descriptor\s+:\s+'L([\w/$]+);'", line)
        if m:
            current = m.group(1)
            continue
        for cls, name, desc in REF.findall(line):
            if cls not in api and not (cls in own and platform_root(api, own, cls)):
                continue
            check_class(cls, current)
            # dexdump writes "name:(args)ret" for methods and "name:Type" for fields
            key = name + desc if desc.startswith("(") else name
            if "%s.%s" % (cls, key) in ALLOWED:
                continue
            since = member_since(api, own, cls, key, set())
            if since is None:
                report("%s.%s (unknown to the API database)" % (cls, key), current)
            elif since > min_api:
                report("%s.%s (API %d)" % (cls, key, since), current)
        for cls in TYPE.findall(line):
            check_class(cls, current)

    if problems:
        print("api-check: calls newer than API %d (the camera's Android 2.3.7):" % min_api, file=sys.stderr)
        for p in sorted(problems):
            print("  %s  <- %s" % (p, ", ".join(sorted(problems[p]))), file=sys.stderr)
        return 1
    print("api-check: every platform call exists on API %d" % min_api)
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Audit prospective/current Git files without printing matched credential values."""
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
RULES = {
    "OpenAI credential": re.compile(r"\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{24,}"),
    "GitHub credential": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})"),
    "AWS credential": re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----"),
    "JWT-shaped value": re.compile(r"\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}"),
    "embedded credential URL": re.compile(r"https?://[^\s/<>]+:[^\s/@<>]+@"),
    "credential assignment": re.compile(r'''(?i)(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|password)\s*[:=]\s*["'](?!\$|YOUR_|EXAMPLE_|REPLACE_)[A-Za-z0-9_+/=-]{16,}["']'''),
    "private host path": re.compile(r"(?i)(?:[A-Z]:[\\/]+Users[\\/]+(?!PUBLIC|USERNAME|YOUR_USER)[A-Za-z0-9_.-]+|/Users/[A-Za-z0-9_.-]+)"),
    "local network address": re.compile(r"\b(?:192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3})\b"),
}


def main():
    top = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "--show-toplevel"], capture_output=True, text=True)
    if top.returncode == 0 and pathlib.Path(top.stdout.strip()).resolve() == ROOT:
        git = ["git", "-C", str(ROOT)]
    else:
        audit_git = ROOT / ".probe" / "publication-audit.git"
        audit_git.parent.mkdir(parents=True, exist_ok=True)
        if not audit_git.exists():
            subprocess.run(["git", "init", "--bare", str(audit_git)], check=True, capture_output=True)
        git = ["git", "--git-dir=" + str(audit_git), "--work-tree=" + str(ROOT)]
    paths = subprocess.check_output(git + ["ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT).decode().split("\0")
    paths = sorted({p for p in paths if p})
    findings = []
    for relative in paths:
        path = ROOT / relative
        sensitive = (relative.startswith((".probe/", ".gradle/", "secrets/", "credentials/"))
                     or path.name in {"auth.json", "diagnostic.json", "usage.json", "DIAGNOSTICS.md", "signing.properties", "keystore.properties"}
                     or path.suffix.lower() in {".jks", ".keystore", ".pem", ".key", ".p12", ".pfx", ".apk", ".aab", ".apks"}
                     or path.name == ".env" or path.name.startswith(".env.") and path.name != ".env.example")
        if sensitive or path.is_symlink():
            findings.append((relative, 0, "sensitive/generated path or symlink"))
            continue
        try:
            text = path.read_text(encoding="utf-8-sig")
        except UnicodeDecodeError:
            findings.append((relative, 0, "binary file requires manual publication review"))
            continue
        for number, line in enumerate(text.splitlines(), 1):
            for label, pattern in RULES.items():
                if pattern.search(line):
                    findings.append((relative, number, label))
    samples = [".probe/session/auth.json", "auth.json", "diagnostic.json", "usage.json", "DIAGNOSTICS.md",
               ".env", ".env.production", "release.jks", "release.keystore", "upload.p12", "private.pem",
               "private.key", "signing.properties", "keystore.properties", "app/build/app.apk", "test.aab"]
    ignore_results = subprocess.check_output(git + ["check-ignore", "--verbose", "--no-index"] + samples, cwd=ROOT, text=True).splitlines()
    ignored = [line.split("\t", 1)[1] for line in ignore_results if "\t" in line]
    for sample in set(samples) - set(ignored):
        findings.append((sample, 0, "required ignore rule is missing"))
    # Ensure recognizers work without storing credential-shaped fixture strings in the tree.
    assert RULES["GitHub credential"].search("gh" + "p_" + "A" * 40)
    assert RULES["private key"].search("-----BEGIN " + "PRIVATE KEY-----")
    for path, line, category in findings:
        print(f"{path}:{line}: {category} (value withheld)")
    print(f"Audited {len(paths)} Git-eligible files; {len(findings)} findings; ignore checks completed.")
    if findings:
        sys.exit(1)


if __name__ == "__main__":
    main()

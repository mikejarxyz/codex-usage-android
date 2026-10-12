"""Generate public, path-free notices for the pinned on-device app-server."""
import argparse
import hashlib
import html
import json
import pathlib
import subprocess
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[1]
CODEX_COMMIT = "cc7ba33601286a751477832b01de7b6f8de0dd9c"
OPENSSL_COMMIT = "aae016bfd52fcad2bc9657c2c782cfdf73b1ed5f"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_url(package):
    source = package.get("source") or ""
    if source.startswith("registry+"):
        return "https://crates.io/api/v1/crates/{}/{}/download".format(package["name"], package["version"])
    if source.startswith("git+"):
        parsed = urllib.parse.urlsplit(source[4:])
        if parsed.username or parsed.password or parsed.scheme != "https":
            raise ValueError("Dependency source is not a public HTTPS URL")
        url = urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, parsed.path.removesuffix(".git"), "", ""))
        return url + "/tree/" + parsed.fragment if parsed.fragment else url
    folder = pathlib.Path(package["manifest_path"]).parent.relative_to(ROOT / ".probe" / "codex").as_posix()
    return "https://github.com/openai/codex/tree/" + CODEX_COMMIT + "/" + folder


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cargo-about", default="cargo-about", help="Path to cargo-about 0.9.2")
    args = parser.parse_args()
    if pathlib.Path(args.cargo_about).is_file():
        args.cargo_about = str(pathlib.Path(args.cargo_about).resolve())
    version = subprocess.check_output([args.cargo_about, "--version"], text=True).strip()
    if not version.endswith("0.9.2"):
        raise ValueError("Use cargo-about 0.9.2 for this notice format")
    source = ROOT / ".probe" / "codex"
    actual = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if actual != CODEX_COMMIT:
        raise ValueError("Codex checkout differs from the pinned native source")
    output = ROOT / ".probe" / "cargo-about-licenses.json"
    subprocess.run([args.cargo_about, "generate", "--locked", "--fail", "--format", "json",
                    "-m", str(source / "codex-rs" / "app-server" / "Cargo.toml"),
                    "-c", str(ROOT / "licenses" / "about.toml"), "-o", str(output)], check=True)
    data = json.loads(output.read_text(encoding="utf-8-sig"))
    packages = []
    for item in data["crates"]:
        package = item["package"]
        packages.append({"name": package["name"], "version": package["version"],
                         "declaredLicense": package["license"], "selectedLicense": item["license"],
                         "source": source_url(package)})
    packages.sort(key=lambda p: (p["name"], p["version"]))
    sections = []
    def section(title, text):
        text = text.replace("\r\n", "\n").replace("\r", "\n")
        sections.append("<section><h2>" + html.escape(title) + "</h2><pre>" + html.escape(text) + "</pre></section>")
    for filename in ["LICENSE", "NOTICE"]:
        section("Project " + filename, (ROOT / filename).read_text(encoding="utf-8"))
    for filename in ["OpenAI-Codex-Apache-2.0.txt", "OpenAI-Codex-NOTICE.txt", "OpenSSL-Apache-2.0.txt", "Ratatui-MIT.txt"]:
        section(filename, (ROOT / "licenses" / filename).read_text(encoding="utf-8"))
    sections.append("<h2>Dependency sources</h2><p>Some dependencies use MPL-2.0. Their unmodified source is available at the exact version links below. Third-party code retains its own license.</p><ul>")
    for package in packages:
        label = package["name"] + " " + package["version"] + " — " + package["selectedLicense"]
        sections.append('<li><a href="' + html.escape(package["source"], quote=True) + '">' + html.escape(label) + '</a></li>')
    sections.append("</ul>")
    for license_record in data["licenses"]:
        users = sorted({u["crate"]["name"] + " " + u["crate"]["version"] for u in license_record["used_by"]})
        section(license_record["id"] + " — " + ", ".join(users), license_record["text"])
    notice = """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Open-source licenses</title><style>body{background:#0f0f0f;color:#f1f1f1;font:14px sans-serif;margin:24px;line-height:1.5}h1{font-size:24px}h2{font-size:18px;overflow-wrap:anywhere}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:12px sans-serif}a{color:#d0d0d0}section{border-top:1px solid #303030;padding-top:16px;margin-top:24px}li{margin:8px 0;overflow-wrap:anywhere}</style>
<h1>Open-source licenses</h1><p>Codex Usage for Android is unofficial and not endorsed by OpenAI. The OpenAI mark is a trademark; the project license grants no trademark rights.</p>
""" + "\n".join(sections) + "</html>\n"
    asset = ROOT / "app" / "src" / "main" / "assets" / "third_party_licenses.html"
    asset.parent.mkdir(parents=True, exist_ok=True)
    asset.write_text(notice, encoding="utf-8", newline="\n")
    manifest = {"generator": version, "target": "aarch64-linux-android", "codexCommit": CODEX_COMMIT,
                "opensslVersion": "3.6.3", "opensslCommit": OPENSSL_COMMIT,
                "cargoLockSha256": digest(source / "codex-rs" / "Cargo.lock"),
                "projectLicenseSha256": digest(ROOT / "LICENSE"), "projectNoticeSha256": digest(ROOT / "NOTICE"),
                "assetSha256": digest(asset), "packages": packages}
    (ROOT / "licenses" / "dependency-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"Generated {len(packages)} package entries and {len(data['licenses'])} license-text variants; no local paths included.")


if __name__ == "__main__":
    main()

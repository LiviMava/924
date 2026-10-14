"""Download public, independently published handbooks; retain provenance and reject duplicates.

Requires pypdf. Originals and extracted pages stay in .runtime; no full-text redistribution.
Only seed URLs and handbook PDF links on seed pages are fetched. No login or search scraping.
"""
import argparse
import concurrent.futures
import hashlib
import io
import json
import pathlib
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import urllib.robotparser
from datetime import datetime, timezone
from html.parser import HTMLParser

from pypdf import PdfReader

ROOT = pathlib.Path(__file__).resolve().parents[1]
AGENT = "RagHandbookResearch/1.0"
MAX_BYTES = 20 * 1024 * 1024
HANDBOOK = re.compile(r"(?:employee|staff|personnel|employment).{0,40}(?:handbook|manual)|(?:员工|員工|職員|职工)\s*手\s*[册冊]", re.I | re.S)


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def fetch(url, maximum=MAX_BYTES):
    url = urllib.parse.quote(url, safe=":/?#[]@!$&'()*+,;=%")
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in ("https", "http") or parsed.username or parsed.password:
        raise ValueError("Only public HTTP(S) source URLs are supported")
    request = urllib.request.Request(url, headers={"User-Agent": AGENT, "Accept": "application/pdf,text/html;q=0.9,*/*;q=0.5"})
    with urllib.request.urlopen(request, timeout=25) as response:
        data = response.read(maximum + 1)
        if len(data) > maximum:
            raise ValueError("Resource exceeds size limit")
        return data, response.url, response.headers.get("Content-Type", "")


def check_robots(url):
    parsed = urllib.parse.urlsplit(url)
    robots_url = urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, "/robots.txt", "", ""))
    try:
        data, _, _ = fetch(robots_url, 512 * 1024)
        parser = urllib.robotparser.RobotFileParser()
        parser.parse(data.decode("utf-8", "replace").splitlines())
        if not parser.can_fetch(AGENT, url):
            raise ValueError("robots.txt disallows this source")
        return "allowed"
    except urllib.error.HTTPError as error:
        if error.code in (404, 410):
            return "not-published"
        raise ValueError("robots.txt unavailable: HTTP " + str(error.code)) from None


class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []
        self.href = None
        self.label = ""

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            self.href = dict(attrs).get("href")
            self.label = ""

    def handle_data(self, value):
        if self.href:
            self.label += value

    def handle_endtag(self, tag):
        if tag == "a" and self.href:
            self.links.append((self.href, self.label))
            self.href = None


def pdf_record(seed, data, url, robots, directory):
    if not data.startswith(b"%PDF-"):
        raise ValueError("Not a PDF")
    reader = PdfReader(io.BytesIO(data), strict=False)
    if reader.is_encrypted or not 2 <= len(reader.pages) <= 500:
        raise ValueError("Encrypted PDF or page count outside 2..500")
    pages = [{"page": i + 1, "text": page.extract_text() or ""} for i, page in enumerate(reader.pages)]
    full = "\n".join(page["text"] for page in pages)
    if not 5000 <= len(full) <= 2_000_000:
        raise ValueError("Insufficient extractable text or text too large")
    if not HANDBOOK.search("\n".join(p["text"] for p in pages[:8])):
        raise ValueError("Opening pages do not identify an employee/staff handbook")
    digest = hashlib.sha256(data).hexdigest()
    if seed.get("sha256") and seed["sha256"] != digest:
        raise ValueError("Source version changed: SHA-256 differs from the pinned evaluation snapshot")
    text_digest = hashlib.sha256(re.sub(r"\s+", "", full).encode()).hexdigest()
    host = urllib.parse.urlsplit(seed["url"]).hostname.removeprefix("www.")
    slug = re.sub(r"[^a-z0-9]+", "-", host.lower()).strip("-")
    filename = f"{slug}-{digest[:10]}.pdf"
    path = directory / "files" / filename
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    write_json(directory / "pages" / (filename + ".json"), pages)
    return {"title": seed.get("title", "Employee handbook"), "sourceUrl": seed["url"], "downloadUrl": url,
            "publisherHost": host, "filename": filename, "sha256": digest, "textSha256": text_digest,
            "bytes": len(data), "pages": len(pages), "characters": len(full), "robots": robots,
            "fetchedAt": datetime.now(timezone.utc).isoformat(), "status": "DOWNLOADED"}


def download(seed, directory):
    try:
        target = seed.get("downloadUrl", seed["url"])
        robots = check_robots(target)
        data, final, _ = fetch(target)
        if data.startswith(b"%PDF-"):
            return pdf_record(seed, data, final, robots, directory)
        html = Links()
        html.feed(data.decode("utf-8", "replace"))
        candidates = []
        for href, label in html.links:
            url = urllib.parse.urljoin(final, href)
            decoded = urllib.parse.unquote(url)
            if (".pdf" in decoded.lower() or "documentcenter/view" in decoded.lower()) and HANDBOOK.search(decoded + " " + label):
                if url not in candidates:
                    candidates.append(url)
        errors = []
        for url in candidates[:4]:
            try:
                time.sleep(0.3)
                robots = check_robots(url)
                pdf, final, _ = fetch(url)
                return pdf_record(seed, pdf, final, robots, directory)
            except Exception as error:
                errors.append(type(error).__name__ + ": " + str(error)[:160])
        raise ValueError("No readable handbook PDF on page" + (": " + "; ".join(errors) if errors else ""))
    except Exception as error:
        return {"sourceUrl": seed["url"], "status": "REJECTED", "reason": type(error).__name__ + ": " + str(error)[:300]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seeds", type=pathlib.Path, default=ROOT / "evals/handbooks/sources.json")
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / ".runtime/handbooks")
    parser.add_argument("--target", type=int, default=100)
    parser.add_argument("--workers", type=int, choices=range(1, 9), default=4)
    parser.add_argument("--retry-rejected", action="store_true")
    args = parser.parse_args()
    manifest_path = args.output / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else []
    exclusions_path = ROOT / "evals/handbooks/excluded.json"
    exclusions = json.loads(exclusions_path.read_text(encoding="utf-8")) if exclusions_path.exists() else {}
    for item in manifest:
        if item.get("publisherHost") in exclusions:
            item.update(status="EXCLUDED", reason=exclusions[item["publisherHost"]])
    seeds = json.loads(args.seeds.read_text(encoding="utf-8"))
    pins = {s["url"]: s.get("sha256") for s in seeds}
    existing = {item["sourceUrl"]: item for item in manifest}
    for url, item in list(existing.items()):
        if item["status"] == "DOWNLOADED":
            file = args.output / "files" / item["filename"]
            if (not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != item["sha256"]
                    or pins.get(url) and pins[url] != item["sha256"]):
                del existing[url]
    manifest = list(existing.values())
    write_json(manifest_path, manifest)
    pending = [s for s in seeds if urllib.parse.urlsplit(s["url"]).hostname.removeprefix("www.") not in exclusions
               and (s["url"] not in existing or (args.retry_rejected and existing[s["url"]]["status"] == "REJECTED"))]
    hashes = {x["sha256"] for x in manifest if x["status"] == "DOWNLOADED"}
    texts = {x["textSha256"] for x in manifest if x["status"] == "DOWNLOADED"}
    hosts = {x["publisherHost"] for x in manifest if x["status"] == "DOWNLOADED"}
    # At most one selected handbook per seed hostname; versions are not counted twice.
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        for start in range(0, len(pending), args.workers):
            if len(hashes) >= args.target:
                break
            batch = [s for s in pending[start:start + args.workers]
                     if urllib.parse.urlsplit(s["url"]).hostname.removeprefix("www.") not in hosts]
            for item in executor.map(lambda s: download(s, args.output), batch):
                if item["status"] == "DOWNLOADED":
                    if item["sha256"] in hashes or item["textSha256"] in texts or item["publisherHost"] in hosts:
                        item["status"] = "DUPLICATE"
                    else:
                        hashes.add(item["sha256"])
                        texts.add(item["textSha256"])
                        hosts.add(item["publisherHost"])
                existing[item["sourceUrl"]] = item
                write_json(manifest_path, list(existing.values()))
                print(json.dumps({"downloaded": len(hashes), "source": item["sourceUrl"], "status": item["status"], "reason": item.get("reason")}), flush=True)
    print(json.dumps({"downloaded": len(hashes), "required": args.target, "manifest": str(manifest_path)}))
    return 0 if len(hashes) >= args.target else 1


if __name__ == "__main__":
    raise SystemExit(main())

"""Resume handbook ingestion through the application's real upload/queue API (no direct DB writes)."""
import argparse
import hashlib
import json
import os
import pathlib
import time
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parents[1]


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


class Api:
    def __init__(self, url):
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in ("127.0.0.1", "localhost", "::1")):
            raise ValueError("Use HTTPS or a loopback HTTP service")
        self.url = url.rstrip("/")
        self.headers = {}
        if os.environ.get("RAG_API_KEY"):
            self.headers["Authorization"] = "Bearer " + os.environ["RAG_API_KEY"]
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({})) if parsed.hostname in ("127.0.0.1", "localhost", "::1") else urllib.request.build_opener()

    def call(self, path, body=None, method=None, content_type="application/json", timeout=180):
        headers = {**self.headers, "Content-Type": content_type}
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(self.url + path, data=body, headers=headers, method=method)
        with self.opener.open(request, timeout=timeout) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    def upload(self, path, filename):
        boundary = "rag" + uuid.uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: application/pdf\r\n\r\n'.encode()
                + path.read_bytes() + f"\r\n--{boundary}--\r\n".encode())
        return self.call("/api/v1/documents", body, "POST", "multipart/form-data; boundary=" + boundary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=pathlib.Path, default=ROOT / ".runtime/handbooks")
    parser.add_argument("--url", default="http://127.0.0.1:8090")
    parser.add_argument("--minimum", type=int, default=100)
    parser.add_argument("--timeout", type=int, default=14400)
    parser.add_argument("--retry-failed", action="store_true")
    parser.add_argument("--watch", action="store_true", help="Pick up new downloads while waiting for ingestion")
    args = parser.parse_args()
    api = Api(args.url)
    questions = ROOT / "evals/handbooks/questions.json"
    if questions.is_file():
        write_json(args.directory / "questions.json", json.loads(questions.read_text(encoding="utf-8")))
    deadline = time.monotonic() + args.timeout
    retried = set()
    previous = None
    while time.monotonic() < deadline:
        manifest = json.loads((args.directory / "manifest.json").read_text(encoding="utf-8"))
        sources = [item for item in manifest if item["status"] == "DOWNLOADED"]
        docs = []
        for offset in range(0, 100000, 500):
            page = api.call(f"/api/v1/documents?limit=500&offset={offset}")
            docs.extend(page)
            if len(page) < 500:
                break
        by_hash = {doc["sha256"]: doc for doc in docs}
        records = []
        for source in sources:
            doc = by_hash.get(source["sha256"])
            if not doc:
                path = (args.directory / "files" / source["filename"]).resolve()
                if path.parent != (args.directory / "files").resolve():
                    raise ValueError("Invalid filename")
                if hashlib.sha256(path.read_bytes()).hexdigest() != source["sha256"]:
                    raise ValueError("Source checksum mismatch: " + source["filename"])
                doc = api.upload(path, source["filename"])
                print(json.dumps({"uploaded": source["filename"], "documentId": doc["id"]}), flush=True)
            if doc["status"] == "FAILED" and args.retry_failed and doc["id"] not in retried:
                doc = api.call("/api/v1/documents/" + doc["id"] + "/retry", {}, "POST")
                retried.add(doc["id"])
            records.append({"filename": source["filename"], "sha256": source["sha256"], "documentId": doc["id"],
                            "status": doc["status"], "chunks": doc["chunkCount"], "indexMode": doc["indexMode"],
                            "collection": doc["vectorCollection"], "error": doc["error"]})
        summary = {"downloaded": len(sources), "ready": sum(r["status"] == "READY" and r["indexMode"] == "HYBRID" for r in records),
                   "failed": sum(r["status"] == "FAILED" for r in records), "pending": sum(r["status"] in ("QUEUED", "PROCESSING") for r in records),
                   "chunks": sum(r["chunks"] for r in records if r["status"] == "READY")}
        write_json(args.directory / "ingestion.json", {"updatedAt": datetime.now(timezone.utc).isoformat(), **summary, "documents": records})
        if summary != previous:
            print(json.dumps(summary), flush=True)
            previous = summary
        if summary["ready"] >= args.minimum and summary["pending"] == 0 and summary["failed"] == 0:
            return 0
        if not args.watch and summary["pending"] == 0:
            break
        time.sleep(10)
    print("Ingestion requirement not satisfied; inspect .runtime/handbooks/ingestion.json", flush=True)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

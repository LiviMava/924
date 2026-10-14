"""Live, evidence-aware regression: real retrieval, real chat, source/page checks and fact assertions.

Expected answers are never sent to either search or chat. Full responses remain local.
This is a bounded regression suite, not a general claim of factual accuracy.
"""
import argparse
import hashlib
import json
import pathlib
import re
import time
import unicodedata
import urllib.request
from datetime import datetime, timezone

from import_handbooks import Api, ROOT, write_json


def normalized(value):
    return " ".join(unicodedata.normalize("NFKC", value).replace("*", "").split())


def patterns_match(patterns, text):
    return all(re.search(pattern, normalized(text), re.I) is not None for pattern in patterns)


def corpus_check(directory, api, minimum, qdrant_url):
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    sources = [m for m in manifest if m["status"] == "DOWNLOADED"]
    docs = []
    for offset in range(0, 100000, 500):
        page = api.call(f"/api/v1/documents?limit=500&offset={offset}")
        docs.extend(page)
        if len(page) < 500:
            break
    by_hash = {doc["sha256"]: doc for doc in docs}
    selected = [by_hash[m["sha256"]] for m in sources if m["sha256"] in by_hash]
    ready = [doc for doc in selected if doc["status"] == "READY" and doc["indexMode"] == "HYBRID"]
    checksums_valid = all(hashlib.sha256((directory / "files" / m["filename"]).read_bytes()).hexdigest() == m["sha256"] for m in sources)
    vector_checks = []
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for collection in sorted({doc["vectorCollection"] for doc in ready}):
        group = [doc for doc in ready if doc["vectorCollection"] == collection]
        body = {"exact": True, "filter": {"must": [{"key": "documentId", "match": {"any": [d["id"] for d in group]}}]}}
        request = urllib.request.Request(qdrant_url.rstrip("/") + "/collections/" + collection + "/points/count",
                                         data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
        with opener.open(request, timeout=60) as response:
            count = json.load(response)["result"]["count"]
        expected = sum(doc["chunkCount"] for doc in group)
        vector_checks.append({"collection": collection, "expectedPoints": expected, "actualPoints": count, "passed": count == expected})
    independent = len({m["sha256"] for m in sources}) == len(sources) == len({m["textSha256"] for m in sources})
    passed = (len(ready) == len(sources) >= minimum and checksums_valid and independent and bool(vector_checks)
              and all(check["passed"] for check in vector_checks))
    return {"passed": passed, "minimum": minimum, "downloaded": len(sources), "readyHybrid": len(ready),
            "pages": sum(m["pages"] for m in sources), "chunks": sum(d["chunkCount"] for d in ready),
            "checksumsValid": checksums_valid, "uniqueFilesAndTexts": independent, "vectors": vector_checks}, docs


def evaluate_case(case, api, by_name, rerank_mode="http"):
    started = time.monotonic()
    result = {"id": case["id"], "query": case["query"], "scope": case["scope"], "negative": case.get("negative", False),
              "expectedDocument": case["expected_document"], "expectedPages": case["sourcePages"],
              "retrievalPassed": False, "answerPassed": False, "citationPassed": False, "passed": False}
    raw = {}
    try:
        doc = by_name[case["expected_document"]]
        if doc["status"] != "READY":
            raise ValueError("Expected source is not READY")
        search = {"query": case["query"], "topK": 6}
        chat = {"question": case["query"], "history": []}
        if case["scope"] == "document":
            search["documentId"] = chat["documentId"] = doc["id"]
        raw["searchInput"] = search
        raw["chatInput"] = chat
        retrieved = raw["retrieval"] = api.call("/api/v1/search", search)
        answer = raw["chat"] = api.call("/api/v1/chat", chat)
        hits, citations = retrieved["hits"], answer["citations"]
        scoped = all(h["documentId"] == doc["id"] for h in hits + citations) if case["scope"] == "document" else True
        expected_hits = [h for h in hits if h["filename"] == case["expected_document"] and h["page"] in case["sourcePages"]]
        result["retrievalPassed"] = scoped and retrieved["retrievalMode"].startswith("HYBRID") and (
            case.get("negative", False) or bool(expected_hits) and patterns_match(case["sourcePatterns"], "\n".join(h["text"] for h in expected_hits)))
        text = answer["answer"]
        result["answer"] = text
        result["answerPassed"] = patterns_match(case["answerPatterns"], text) and not any(
            re.search(p, normalized(text), re.I) for p in case.get("forbiddenPatterns", []))
        numbers = [int(n) for n in re.findall(r"\[(\d+)\]", text)]
        valid = all(1 <= n <= len(citations) for n in numbers)
        referenced = [citations[n - 1] for n in numbers] if valid else []
        correct = [h for h in referenced if h["filename"] == case["expected_document"] and h["page"] in case["sourcePages"]]
        result["citationPassed"] = valid and scoped and (case.get("negative", False) or
            bool(correct) and patterns_match(case["sourcePatterns"], "\n".join(h["text"] for h in correct)))
        result["citedSources"] = [{"filename": h["filename"], "page": h["page"]} for h in referenced]
        result["retrievalMode"] = retrieved["retrievalMode"]
        result["chatRetrievalMode"] = answer["retrievalMode"]
        expected_mode = "HYBRID_LLM_RERANKED" if rerank_mode == "llm" else "HYBRID_RERANKED"
        result["rerankPassed"] = (not hits or retrieved["retrievalMode"] == expected_mode) and (not citations or answer["retrievalMode"] == expected_mode)
        result["passed"] = all(result.get(key, False) for key in ("retrievalPassed", "answerPassed", "citationPassed", "rerankPassed")) and answer["retrievalMode"].startswith("HYBRID")
    except Exception as error:
        result["error"] = type(error).__name__ + ": " + str(error)[:300]
    result["elapsedMs"] = round((time.monotonic() - started) * 1000)
    return result, raw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=pathlib.Path, default=ROOT / ".runtime/handbooks")
    parser.add_argument("--questions", type=pathlib.Path, default=ROOT / "evals/handbooks/questions.json")
    parser.add_argument("--url", default="http://127.0.0.1:8090")
    parser.add_argument("--qdrant-url", default="http://127.0.0.1:6333")
    parser.add_argument("--minimum", type=int, default=100)
    parser.add_argument("--rerank-mode", choices=["http", "llm"], default="http")
    parser.add_argument("--publish", action="store_true", help="Publish compact results only when every check passes with >=100 files")
    args = parser.parse_args()
    api = Api(args.url)
    cases = json.loads(args.questions.read_text(encoding="utf-8"))
    if len(cases) < 5 or len({case["id"] for case in cases}) != len(cases):
        raise ValueError("Need >=5 independently identified questions")
    corpus, docs = corpus_check(args.directory, api, args.minimum, args.qdrant_url)
    print(json.dumps({"corpus": corpus}), flush=True)
    results, full = [], []
    for case in cases:
        result, raw = evaluate_case(case, api, {doc["filename"]: doc for doc in docs}, args.rerank_mode)
        results.append(result)
        full.append({"result": result, "responses": raw})
        print(json.dumps(result, ensure_ascii=False), flush=True)
    stamp = datetime.now(timezone.utc)
    passed = sum(case["passed"] for case in results)
    report = {"finishedAt": stamp.isoformat(), "corpus": corpus, "rerankMode": args.rerank_mode, "passed": passed, "total": len(results),
              "allPassed": corpus["passed"] and passed == len(results), "cases": results,
              "limitations": "Snapshot-specific regression with deterministic fact assertions; not a general factuality guarantee. Source scoping is not an authorization boundary."}
    name = stamp.strftime("%Y%m%dT%H%M%S%fZ")
    write_json(args.directory / "evaluation-runs" / (name + ".json"), {"summary": report, "cases": full})
    write_json(args.directory / "evaluation-latest.json", report)
    write_json(args.directory / "questions.json", cases)
    if args.publish and report["allPassed"] and corpus["downloaded"] >= 100:
        write_json(ROOT / "evals/handbooks/report.json", report)
    print(json.dumps({"passed": passed, "total": len(results), "allPassed": report["allPassed"]}), flush=True)
    return 0 if report["allPassed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

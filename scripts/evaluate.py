"""Read-only retrieval smoke evaluation; upload samples first. Python standard library only."""
import argparse
import json
import os
import pathlib
import urllib.error
import urllib.request

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8090")
    parser.add_argument("--dataset", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1] / "evals/questions.jsonl")
    parser.add_argument("--top-k", type=int, default=6)
    args = parser.parse_args()
    headers = {"Content-Type": "application/json"}
    token = os.environ.get("RAG_API_KEY")
    if token:
        headers["Authorization"] = "Bearer " + token
    examples = [json.loads(line) for line in args.dataset.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not examples:
        raise SystemExit("Evaluation dataset is empty.")
    successes = 0
    for item in examples:
        payload = json.dumps({"query": item["query"], "topK": args.top_k}).encode()
        request = urllib.request.Request(args.url.rstrip("/") + "/api/v1/search", data=payload, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            raise SystemExit(f"Evaluation request failed: HTTP {error.code}. Check service and RAG_API_KEY.") from None
        hits = result.get("hits", [])
        matched = [hit for hit in hits if hit["filename"] == item["expected_document"]]
        text = "\n".join(hit["text"] for hit in matched)
        passed = bool(matched) and all(term in text for term in item.get("expected_terms", []))
        successes += passed
        print(json.dumps({"query": item["query"], "passed": passed, "mode": result["retrievalMode"],
                          "documents": [hit["filename"] for hit in hits]}, ensure_ascii=False))
    print(json.dumps({"passed": successes, "total": len(examples), "retrieval_smoke_pass_rate": successes / len(examples)}))
    return 0 if successes == len(examples) else 1

if __name__ == "__main__":
    raise SystemExit(main())

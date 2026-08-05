#!/usr/bin/env python3
"""
explore_mfcs_schema.py
======================
Connects to Oracle MFCS (Merchandising Foundation Cloud Service, PRD1) using an
OAuth2 client_credentials flow, walks every GET endpoint defined in the Postman
collection, fetches ONE record from each (limit=1), stores the raw JSON, and
derives a schema summary for each endpoint.

Safety: every request that supports paging is capped at limit=1 so we never pull
large data sets. Endpoints that don't support 'limit' are still lightweight
reference/status calls.

Usage:
    pip install -r requirements.txt
    python explore_mfcs_schema.py \
        --collection "PRD1 - Johns Production Swagger.postman_collection.json"

Outputs (under ./schemas/):
    raw/<endpoint>.json        - raw response body (pretty-printed)
    schema/<endpoint>.json     - inferred field/type schema for that endpoint
    _index.json                - machine-readable run summary
    _report.md                 - human-readable report of every endpoint
"""

import argparse
import base64
import json
import os
import re
import sys
import time
from pathlib import Path
from urllib.parse import urlencode

import requests

# --------------------------------------------------------------------------- #
# Config / .env loading (no external dependency)
# --------------------------------------------------------------------------- #

def load_env(env_path: Path) -> dict:
    """Minimal .env parser. Does not override already-set OS env vars."""
    cfg = {}
    if env_path.exists():
        for line in env_path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, val = line.partition("=")
            cfg[key.strip()] = val.strip().strip('"').strip("'")
    # OS env wins (handy for CI / not writing secrets to disk)
    for k in list(cfg.keys()):
        if os.environ.get(k):
            cfg[k] = os.environ[k]
    return cfg


# --------------------------------------------------------------------------- #
# OAuth2
# --------------------------------------------------------------------------- #

def get_access_token(cfg: dict, verbose=True) -> str:
    token_url = cfg["MFCS_TOKEN_URL"]
    client_id = cfg.get("MFCS_CLIENT_ID", "")
    client_secret = cfg.get("MFCS_CLIENT_SECRET", "")
    scope = cfg.get("MFCS_SCOPE", "")
    grant_type = cfg.get("MFCS_GRANT_TYPE", "client_credentials")
    auth_style = cfg.get("MFCS_TOKEN_AUTH_STYLE", "basic").lower()

    if not client_id or not client_secret:
        raise SystemExit(
            "ERROR: MFCS_CLIENT_ID / MFCS_CLIENT_SECRET are empty. "
            "Fill them into .env before running."
        )

    data = {"grant_type": grant_type}
    if scope:
        data["scope"] = scope
    headers = {
        "Content-Type": "application/x-www-form-urlencoded",
        "Accept": "application/json",
    }

    if auth_style == "basic":
        raw = f"{client_id}:{client_secret}".encode("utf-8")
        headers["Authorization"] = "Basic " + base64.b64encode(raw).decode("ascii")
    else:  # 'body'
        data["client_id"] = client_id
        data["client_secret"] = client_secret

    if verbose:
        print(f"[auth] POST {token_url}  (style={auth_style}, scope={scope})")
    resp = requests.post(token_url, headers=headers, data=data, timeout=60)
    if resp.status_code != 200:
        raise SystemExit(
            f"[auth] token request failed: HTTP {resp.status_code}\n{resp.text[:1000]}"
        )
    tok = resp.json()
    access = tok.get("access_token")
    if not access:
        raise SystemExit(f"[auth] no access_token in response: {tok}")
    if verbose:
        print(f"[auth] OK - token type={tok.get('token_type')} "
              f"expires_in={tok.get('expires_in')}")
    return access


# --------------------------------------------------------------------------- #
# Postman collection parsing
# --------------------------------------------------------------------------- #

def sanitize(name: str) -> str:
    s = re.sub(r"[^A-Za-z0-9]+", "_", name).strip("_")
    return s[:120]


def flatten_requests(collection: dict):
    """Yield (folder_path, request_item) for every leaf request."""
    def walk(items, path):
        for it in items:
            if "item" in it:
                yield from walk(it["item"], path + [it.get("name", "")])
            else:
                yield path, it
    yield from walk(collection.get("item", []), [])


def build_get_endpoints(collection: dict):
    """Return list of dicts describing each GET endpoint."""
    eps = []
    for path, it in flatten_requests(collection):
        req = it.get("request")
        if not isinstance(req, dict) or req.get("method") != "GET":
            continue
        url = req.get("url", {})
        if isinstance(url, str):
            raw = url
            query = []
            variables = []
        else:
            raw = url.get("raw", "")
            query = url.get("query", []) or []
            variables = url.get("variable", []) or []

        base_path = raw.split("?", 1)[0]  # strip any placeholder query string
        supports_limit = any((q.get("key") == "limit") for q in query)
        path_vars = re.findall(r"/:([A-Za-z0-9_]+)", base_path)

        eps.append({
            "folder": " / ".join([p for p in path if p]),
            "name": it.get("name", ""),
            "raw_path": base_path,
            "supports_limit": supports_limit,
            "path_vars": path_vars,
            "url_variables": {v.get("key"): v.get("value") for v in variables},
            "query_defs": [
                {"key": q.get("key"), "value": q.get("value"),
                 "disabled": bool(q.get("disabled"))}
                for q in query
            ],
        })
    return eps


# --------------------------------------------------------------------------- #
# Schema inference
# --------------------------------------------------------------------------- #

def infer_schema(value, max_depth=6, _depth=0):
    """Recursively describe the shape of a JSON value."""
    if _depth > max_depth:
        return {"type": "..."}
    if isinstance(value, dict):
        return {
            "type": "object",
            "fields": {k: infer_schema(v, max_depth, _depth + 1)
                       for k, v in value.items()},
        }
    if isinstance(value, list):
        if not value:
            return {"type": "array", "items": {"type": "unknown"}, "count": 0}
        # merge item schemas (use first, note count)
        return {
            "type": "array",
            "count": len(value),
            "items": infer_schema(value[0], max_depth, _depth + 1),
        }
    if isinstance(value, bool):
        return {"type": "boolean", "example": value}
    if isinstance(value, int):
        return {"type": "integer", "example": value}
    if isinstance(value, float):
        return {"type": "number", "example": value}
    if value is None:
        return {"type": "null"}
    # string
    s = str(value)
    return {"type": "string", "example": (s[:60] + "…") if len(s) > 60 else s}


def field_list(schema, prefix=""):
    """Flatten an inferred schema into dotted field -> type lines."""
    lines = []
    t = schema.get("type")
    if t == "object":
        for k, v in schema.get("fields", {}).items():
            key = f"{prefix}{k}"
            vt = v.get("type")
            if vt in ("object", "array"):
                lines.append((key, vt))
                lines.extend(field_list(v if vt == "object" else v.get("items", {}),
                                        prefix=key + ("." if vt == "object" else "[]. ")))
            else:
                lines.append((key, vt))
    elif t == "array":
        lines.extend(field_list(schema.get("items", {}), prefix=prefix + "[]."))
    return lines


# --------------------------------------------------------------------------- #
# ID resolution for :path variables
# --------------------------------------------------------------------------- #

def harvest_values(obj, pool):
    """Collect scalar key->value samples from a response for path-var resolution."""
    if isinstance(obj, dict):
        for k, v in obj.items():
            if isinstance(v, (str, int)) and not isinstance(v, bool):
                pool.setdefault(k.lower(), v)
            harvest_values(v, pool)
    elif isinstance(obj, list):
        for v in obj[:3]:
            harvest_values(v, pool)


def resolve_path_var(var, pool):
    """Best-effort: match a :pathVar to a harvested value."""
    cand = [var.lower(), var.lower().rstrip("id"), var.lower() + "id",
            "id", var.lower().replace("id", "")]
    for c in cand:
        if c and c in pool:
            return pool[c]
    # loose contains match
    for k, v in pool.items():
        if var.lower() in k or k in var.lower():
            return v
    return None


# --------------------------------------------------------------------------- #
# Fetching
# --------------------------------------------------------------------------- #

def fetch_endpoint(ep, cfg, token, pool, limit=1, timeout=90):
    base_url = cfg["MFCS_BASE_URL"].rstrip("/")
    url = ep["raw_path"].replace("{{baseUrl}}", base_url)

    # resolve path variables
    unresolved = []
    for var in ep["path_vars"]:
        val = ep["url_variables"].get(var) or resolve_path_var(var, pool)
        if val in (None, "", "string", ":" + var):
            unresolved.append(var)
        else:
            url = url.replace(f"/:{var}", f"/{val}")

    params = {}
    if ep["supports_limit"]:
        params["limit"] = limit

    result = {
        "folder": ep["folder"], "name": ep["name"],
        "url": url, "params": params,
        "supports_limit": ep["supports_limit"],
        "path_vars": ep["path_vars"], "unresolved_path_vars": unresolved,
    }

    if unresolved:
        result["status"] = "skipped_needs_id"
        result["note"] = f"needs value(s) for path var(s): {', '.join(unresolved)}"
        return result, None

    headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
    try:
        r = requests.get(url, headers=headers, params=params, timeout=timeout)
    except requests.RequestException as e:
        result["status"] = "error"
        result["error"] = str(e)
        return result, None

    result["http_status"] = r.status_code
    result["final_url"] = r.url
    ctype = r.headers.get("Content-Type", "")
    body = None
    if "json" in ctype.lower():
        try:
            body = r.json()
        except ValueError:
            body = None
    if r.status_code == 200 and body is not None:
        result["status"] = "ok"
        harvest_values(body, pool)
    elif r.status_code == 200:
        result["status"] = "ok_nonjson"
        result["preview"] = r.text[:300]
    else:
        result["status"] = f"http_{r.status_code}"
        result["preview"] = (r.text or "")[:500]
    return result, body


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--collection", required=True, help="Path to the Postman collection JSON")
    ap.add_argument("--env", default=".env", help="Path to .env file")
    ap.add_argument("--outdir", default="schemas", help="Output directory")
    ap.add_argument("--limit", type=int, default=1, help="Max records per endpoint (default 1)")
    ap.add_argument("--delay", type=float, default=0.3, help="Delay between calls (seconds)")
    ap.add_argument("--only", default=None, help="Substring filter on endpoint name/folder")
    ap.add_argument("--two-pass", action="store_true",
                    help="After collection GETs, retry :id endpoints using harvested ids")
    args = ap.parse_args()

    cfg = load_env(Path(args.env))
    for req_key in ("MFCS_BASE_URL", "MFCS_TOKEN_URL"):
        if not cfg.get(req_key):
            raise SystemExit(f"ERROR: {req_key} missing from {args.env}")

    collection = json.loads(Path(args.collection).read_text(encoding="utf-8"))
    endpoints = build_get_endpoints(collection)
    if args.only:
        f = args.only.lower()
        endpoints = [e for e in endpoints
                     if f in e["name"].lower() or f in e["folder"].lower()]

    print(f"[collection] {collection.get('info', {}).get('name')}")
    print(f"[collection] {len(endpoints)} GET endpoints to probe (limit={args.limit})\n")

    outdir = Path(args.outdir)
    (outdir / "raw").mkdir(parents=True, exist_ok=True)
    (outdir / "schema").mkdir(parents=True, exist_ok=True)

    token = get_access_token(cfg)
    print()

    pool = {}
    index = []

    # Order so collection (no path var) endpoints run first to populate the id pool
    endpoints.sort(key=lambda e: (len(e["path_vars"]) > 0, e["folder"], e["name"]))

    def run(ep):
        label = f"{ep['folder']} :: {ep['name']}"
        res, body = fetch_endpoint(ep, cfg, token, pool, limit=args.limit)
        tag = res.get("status")
        http = res.get("http_status", "")
        print(f"  [{tag:>16}] {http:>3}  {label}")
        stem = sanitize(f"{ep['folder']}__{ep['name']}") or sanitize(ep["name"])
        if body is not None:
            (outdir / "raw" / f"{stem}.json").write_text(
                json.dumps(body, indent=2, ensure_ascii=False)[:2_000_000],
                encoding="utf-8")
            schema = infer_schema(body)
            (outdir / "schema" / f"{stem}.json").write_text(
                json.dumps(schema, indent=2, ensure_ascii=False), encoding="utf-8")
            res["field_count"] = len(field_list(schema))
        res["file_stem"] = stem
        index.append(res)
        time.sleep(args.delay)
        return res

    print("[pass 1] probing endpoints...")
    for ep in endpoints:
        run(ep)

    # optional second pass: retry skipped :id endpoints now that pool is populated
    if args.two_pass:
        retry = [e for e in endpoints if e["path_vars"]]
        print(f"\n[pass 2] retrying {len(retry)} path-variable endpoints with harvested ids...")
        # remove their pass-1 skip entries
        skipped_stems = {sanitize(f"{e['folder']}__{e['name']}") for e in retry}
        index[:] = [r for r in index if r.get("file_stem") not in skipped_stems
                    or r.get("status") != "skipped_needs_id"]
        for ep in retry:
            run(ep)

    # write summaries
    write_reports(outdir, collection, index, args)
    ok = sum(1 for r in index if r.get("status") == "ok")
    print(f"\n[done] {ok}/{len(index)} endpoints returned data. "
          f"See {outdir}/_report.md")


def write_reports(outdir, collection, index, args):
    (outdir / "_index.json").write_text(
        json.dumps({
            "collection": collection.get("info", {}).get("name"),
            "base_url": None,
            "limit": args.limit,
            "endpoints": index,
        }, indent=2, ensure_ascii=False), encoding="utf-8")

    lines = [f"# MFCS Schema Exploration Report",
             f"", f"Collection: **{collection.get('info', {}).get('name')}**",
             f"Endpoints probed: **{len(index)}** (limit={args.limit} record each)", ""]
    from collections import Counter, defaultdict
    status_counts = Counter(r.get("status") for r in index)
    lines.append("## Status summary")
    lines.append("")
    for k, v in status_counts.most_common():
        lines.append(f"- `{k}`: {v}")
    lines.append("")

    by_folder = defaultdict(list)
    for r in index:
        by_folder[r["folder"]].append(r)

    for folder in sorted(by_folder):
        lines.append(f"## {folder}")
        lines.append("")
        for r in sorted(by_folder[folder], key=lambda x: x["name"]):
            status = r.get("status")
            fc = r.get("field_count", "")
            lines.append(f"### {r['name']}  —  `{status}`")
            lines.append(f"- URL: `{r.get('url')}`")
            if r.get("params"):
                lines.append(f"- Params: `{r['params']}`")
            if r.get("note"):
                lines.append(f"- Note: {r['note']}")
            if r.get("preview"):
                prev = r["preview"].replace("\n", " ")[:300]
                lines.append(f"- Preview: `{prev}`")
            if fc != "":
                lines.append(f"- Fields discovered: {fc}  "
                             f"(schema: `schema/{r['file_stem']}.json`)")
            lines.append("")
    (outdir / "_report.md").write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    main()

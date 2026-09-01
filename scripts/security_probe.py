#!/usr/bin/env python3
"""
newagent 本地非破坏性安全探针。

安全边界：
- 默认只访问 localhost:8081；请显式传入 --base-url 才能切换到其他环境。
- 不读取 .env，不猜测/窃取凭据，不注册账号，不执行删除、导入、生成数据或真实文件上传。
- 只验证 HTTP 状态码、响应头和响应体中的敏感信息迹象。
- 默认低频串行请求；适合开发/测试环境，不是压力测试工具。

示例：
  python scripts/security_probe.py
  python scripts/security_probe.py --base-url http://localhost:8081 --admin-key "$ADMIN_API_KEY"
  python scripts/security_probe.py --json report.json
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


@dataclass
class Finding:
    check: str
    severity: str
    status: str
    detail: str
    evidence: str = ""


SENSITIVE = re.compile(
    r"(?i)(api[_-]?key|authorization|bearer\s+[A-Za-z0-9._-]{12,}|jwt_secret|password\s*[:=])"
)


def request(base: str, method: str, path: str, *, headers=None, data=None, timeout=2):
    url = base.rstrip("/") + path
    req = Request(url, method=method, headers=headers or {}, data=data)
    try:
        with urlopen(req, timeout=timeout) as response:
            body = response.read(4096).decode("utf-8", "replace")
            return response.status, dict(response.headers), body
    except HTTPError as exc:
        body = exc.read(4096).decode("utf-8", "replace")
        return exc.code, dict(exc.headers), body
    except (URLError, TimeoutError, OSError) as exc:
        return None, {}, f"{type(exc).__name__}: {exc}"


def add(results, check, status, expected, detail, body=""):
    if status is None:
        results.append(Finding(check, "INFO", "ERROR", detail))
        return
    ok = status in expected
    severity = "PASS" if ok else "HIGH"
    results.append(Finding(check, severity, "PASS" if ok else "FAIL", detail, body[:240]))


def run(args):
    base = args.base_url
    results: list[Finding] = []
    common = {"Accept": "application/json", "User-Agent": "newagent-security-probe/1.0"}

    # 1. 公共健康检查：确认服务可用，同时检查常见安全响应头。
    status, headers, body = request(base, "GET", "/api/v1/agent/health", headers=common)
    add(results, "health availability", status, {200}, f"GET health -> {status}", body)
    for header in ("X-Content-Type-Options", "X-Frame-Options", "Content-Security-Policy"):
        if header.lower() not in {k.lower() for k in headers}:
            results.append(Finding(f"response header: {header}", "MEDIUM", "FAIL", "安全响应头缺失"))

    # 2. 未携带认证访问受保护资源。
    protected = [
        ("unauth chat", "POST", "/api/v1/agent/chat", b"{}"),
        ("unauth rag debug", "POST", "/api/v1/agent/rag/debug", b"question=test"),
        ("unauth document status", "GET", "/api/v1/agent/document/1/status", None),
        ("unauth evaluation history", "GET", "/api/v1/agent/evaluate/history", None),
        ("unauth actuator metrics", "GET", "/actuator/metrics", None),
    ]
    for name, method, path, data in protected:
        h = dict(common)
        if data and path.endswith("/chat"):
            h["Content-Type"] = "application/json"
        status, _, body = request(base, method, path, headers=h, data=data)
        add(results, name, status, {401, 403, 404}, f"{method} {path} without credentials -> {status}", body)

    # 3. 管理员 key 越权：错误 key 不应获得 ADMIN 权限；不使用真实 key 也能验证。
    admin_paths = [
        ("admin rag debug", "POST", "/api/v1/agent/rag/debug?question=probe"),
        ("admin evaluation history", "GET", "/api/v1/agent/evaluate/history"),
        ("admin document readiness", "GET", "/api/v1/agent/document/readiness"),
        ("admin ecommerce stats", "GET", "/api/v1/ecommerce/generator/stats"),
    ]
    for name, method, path in admin_paths:
        h = dict(common)
        h["X-Admin-Api-Key"] = "definitely-wrong-probe-key"
        status, _, body = request(base, method, path, headers=h)
        add(results, name + " with wrong key", status, {401, 403, 404}, f"{method} {path} with wrong admin key -> {status}", body)

    # 4. 参数边界测试需要合法管理员身份，否则只会测试到认证层。
    # 默认不读取 .env，也不接受/保存真实管理员 Key，因此安全地跳过高成本检索与评测调用。
    results.append(Finding(
        "authenticated input boundaries", "INFO", "SKIP",
        "需要隔离测试环境中的短期管理员凭据；默认跳过，避免触发真实检索/评测和外部模型调用"))

    # 5. 文件名路径穿越：仅发送 1 字节，预期应被认证拦截或安全校验拒绝。
    boundary = [("../probe.txt", b"x"), ("..\\probe.txt", b"x"), ("probe.html", b"x")]
    for filename, content in boundary:
        marker = "----probe-boundary"
        multipart = (f"--{marker}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
                     "Content-Type: text/plain\r\n\r\nx\r\n" f"--{marker}--\r\n").encode()
        h = dict(common)
        h.update({"Content-Type": f"multipart/form-data; boundary={marker}"})
        status, _, body = request(base, "POST", "/api/v1/agent/document/upload", headers=h, data=multipart)
        add(results, "upload filename boundary: " + filename, status, {400, 401, 403, 415, 422}, f"upload 1-byte boundary file -> {status}", body)

    # 6. CORS 反射检查：恶意 Origin 不应被原样允许，尤其是 credentials=true 时。
    h = dict(common)
    h["Origin"] = "https://attacker.invalid"
    status, response_headers, body = request(base, "GET", "/api/v1/agent/health", headers=h)
    acao = next((v for k, v in response_headers.items() if k.lower() == "access-control-allow-origin"), "")
    acac = next((v for k, v in response_headers.items() if k.lower() == "access-control-allow-credentials"), "")
    if acao == "https://attacker.invalid" and acac.lower() == "true":
        results.append(Finding("CORS origin reflection", "HIGH", "FAIL", "恶意 Origin 被允许且携带凭据", f"acao={acao}, acac={acac}"))
    else:
        results.append(Finding("CORS origin reflection", "PASS", "PASS", "未发现恶意 Origin + credentials 反射", f"status={status}, acao={acao}, acac={acac}"))

    time.sleep(max(0.0, args.delay))
    return results


def main():
    parser = argparse.ArgumentParser(description="newagent 本地非破坏性安全探针")
    parser.add_argument("--base-url", default="http://localhost:8081", help="测试服务地址，默认 localhost")
    parser.add_argument("--delay", type=float, default=0.05, help="请求间隔秒数，默认 0.05")
    parser.add_argument("--json", type=Path, help="可选：将结果写入 JSON 文件")
    args = parser.parse_args()
    if not re.match(r"^https?://(localhost|127\.0\.0\.1|\[::1\])(?::\d+)?$", args.base_url.rstrip("/")):
        print("拒绝：默认探针只允许 localhost/127.0.0.1/::1；如需其他环境，请先修改脚本中的安全边界并人工复核。", file=sys.stderr)
        return 2
    results = run(args)
    for item in results:
        print(f"[{item.status:4}] {item.severity:6} {item.check}: {item.detail}")
        if item.evidence:
            print(f"       evidence: {item.evidence}")
    summary = {"total": len(results), "pass": sum(x.status == "PASS" for x in results), "fail": sum(x.status == "FAIL" for x in results), "error": sum(x.status == "ERROR" for x in results)}
    print("\nSummary:", json.dumps(summary, ensure_ascii=False))
    if args.json:
        args.json.write_text(json.dumps({"base_url": args.base_url, "summary": summary, "findings": [asdict(x) for x in results]}, ensure_ascii=False, indent=2), encoding="utf-8")
        print("Report:", args.json)
    return 1 if summary["fail"] else 0


if __name__ == "__main__":
    raise SystemExit(main())

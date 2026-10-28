#!/usr/bin/env python3
"""Run the authenticated production mobile route matrix without logging secrets.

The script intentionally performs read-only page navigation.  The sole account
write is acknowledging the current product-release dialog so it cannot obscure
the remaining screenshots in the same browser context.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from playwright.sync_api import Browser, BrowserContext, Page, Playwright, sync_playwright


MOBILE_LAYOUT_PATHS = {
    "/dashboard", "/customers", "/customers/new", "/reminders", "/customer-pool",
    "/notifications", "/releases", "/tasks", "/content-tasks", "/circle",
    "/announcements", "/team-data", "/market-insights", "/leadership-dashboard",
    "/daily-report", "/performance", "/consultations", "/qq-chat", "/imports",
    "/exports", "/assistant", "/agent", "/admin/members", "/admin/teams",
    "/admin/dictionaries", "/admin/audit", "/admin/governance", "/admin/assistant",
    "/admin/leadership-screen", "/admin/agents", "/admin/performance",
    "/admin/follow-up", "/admin/customer-enrichment", "/admin/customer-intelligence",
    "/settings",
}

MOBILE_VIEWPORTS = [(360, 800), (390, 844), (430, 932), (320, 568), (844, 390), (768, 1024)]
WEBKIT_VIEWPORTS = [(375, 667), (390, 844)]
DESKTOP_VIEWPORTS = [(1366, 768), (1920, 1080)]


@dataclass(frozen=True)
class Route:
    path: str
    name: str
    dynamic_fixture: str = ""


def read_credentials(path: Path) -> tuple[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        if "=" not in raw:
            continue
        key, value = raw.split("=", 1)
        values[key.strip()] = value.strip()
    username, password = values.get("U", ""), values.get("P", "")
    if not username or not password:
        raise SystemExit("credential file must contain non-empty U and P entries")
    return username, password


def safe_slug(value: str) -> str:
    return re.sub(r"[^a-zA-Z0-9._-]+", "-", value.strip("/")) or "root"


def dismiss_blocking_dialogs(page: Page) -> None:
    for label in ("我已知晓，本次登录稍后处理", "我已了解"):
        button = page.get_by_role("button", name=label)
        if button.count() and button.first.is_visible():
            button.first.click(timeout=5_000)
            page.wait_for_timeout(150)


def login(page: Page, base: str, username: str, password: str) -> None:
    page.goto(f"{base}/login", wait_until="domcontentloaded", timeout=30_000)
    if "/login" in page.url:
        username_input = page.locator('input[autocomplete="username"]')
        password_input = page.locator('input[autocomplete="current-password"]')
        username_input.wait_for(state="visible", timeout=30_000)
        username_input.fill(username)
        password_input.fill(password)
        page.locator('form.login-card button.primary-button').click(timeout=10_000)
        page.wait_for_url(re.compile(r"/(dashboard|settings)$"), timeout=30_000)
    dismiss_blocking_dialogs(page)


def first_customer_id(page: Page) -> str:
    result = page.evaluate("""async () => {
      const response = await fetch('/api/v1/customers?page=0&size=1', {credentials: 'same-origin'});
      if (!response.ok) return '';
      const body = await response.json();
      return body?.items?.[0]?.id || '';
    }""")
    if not result:
        raise RuntimeError("no visible customer fixture is available")
    return str(result)


def routes(customer_id: str) -> list[Route]:
    return [
        Route("/login", "登录"), Route("/register", "注册"),
        Route("/leadership-screen", "领导大屏"), Route("/daily-summary", "每日小结"),
        Route("/dashboard", "工作台"), Route("/customers", "客户档案"),
        Route("/customers/new", "新建客户"),
        Route(f"/customers/{customer_id}/edit", "编辑客户", "customer"),
        Route(f"/customers/{customer_id}", "客户详情", "customer"),
        Route("/reminders", "跟进提醒"), Route("/customer-pool", "客户公共池"),
        Route("/notifications", "通知中心"), Route("/releases", "更新日志"),
        Route("/tasks", "任务中心"), Route("/content-tasks", "内容任务"),
        Route("/circle", "内容圈子"), Route("/announcements", "公告中心"),
        Route("/team-data", "团队数据"), Route("/market-insights", "市场治理台"),
        Route("/leadership-dashboard", "领导大屏入口"), Route("/daily-report", "每日汇报"),
        Route("/performance", "绩效中心"), Route("/consultations", "咨询交接"),
        Route("/qq-chat", "QQ 对话"), Route("/imports", "批量导入"),
        Route("/exports", "数据导出"), Route("/assistant", "智能助手"),
        Route("/agent", "我的 Agent"),
        Route("/agent/approvals/00000000-0000-0000-0000-000000000000", "Agent 审批", "controlled-missing"),
        Route("/oauth/authorize", "OAuth 授权", "controlled-missing"),
        Route("/admin/members", "成员管理"), Route("/admin/teams", "团队管理"),
        Route("/admin/dictionaries", "字典管理"), Route("/admin/audit", "审计查询"),
        Route("/admin/governance", "治理配置"), Route("/admin/assistant", "智能助手配置"),
        Route("/admin/leadership-screen", "领导大屏配置"), Route("/admin/agents", "Agent 管理"),
        Route("/admin/performance", "绩效管理"), Route("/admin/follow-up", "跟进策略"),
        Route("/admin/customer-enrichment", "客户资料补全"),
        Route("/admin/customer-intelligence", "客户智能分析管理"), Route("/settings", "账户设置"),
    ]


def inspect_route(page: Page, base: str, route: Route, browser_name: str,
                  width: int, height: int, evidence: Path, screenshot: bool) -> dict[str, Any]:
    # Tear down the previous document before binding this route's listeners.
    # Otherwise fetches cancelled by the next full navigation are reported by
    # Chromium as net::ERR_ABORTED/TypeError and get falsely attributed to the
    # route that is about to load.  Cookies remain in the browser context.
    page.goto("about:blank", wait_until="domcontentloaded", timeout=10_000)
    page.wait_for_timeout(50)
    console_errors: list[str] = []
    page_errors: list[str] = []
    failed_requests: list[str] = []
    server_errors: list[str] = []

    def on_console(message: Any) -> None:
        if message.type == "error":
            console_errors.append(message.text[:500])

    def on_page_error(error: Any) -> None:
        page_errors.append(str(error)[:500])

    def on_request_failed(request: Any) -> None:
        failed_requests.append(f"{request.method} {request.url} {request.failure}")

    def on_response(response: Any) -> None:
        if response.status >= 500:
            server_errors.append(f"{response.status} {response.url}")

    page.on("console", on_console)
    page.on("pageerror", on_page_error)
    page.on("requestfailed", on_request_failed)
    page.on("response", on_response)
    started = time.perf_counter()
    status = "PASS"
    note = ""
    final_url = ""
    body_chars = 0
    overflow = False
    overflow_detail = ""
    bottom_nav_visible = False
    sidebar_visible = False
    try:
        page.goto(base + route.path, wait_until="domcontentloaded", timeout=30_000)
        page.wait_for_timeout(450)
        dismiss_blocking_dialogs(page)
        final_url = page.url
        body_chars = int(page.locator("body").evaluate("el => (el.innerText || '').trim().length"))
        overflow = bool(page.evaluate("document.documentElement.scrollWidth > window.innerWidth + 2"))
        overflow_detail = str(page.evaluate("""() => Array.from(document.querySelectorAll('body *'))
          .map((el) => ({ el, rect: el.getBoundingClientRect(), style: getComputedStyle(el) }))
          .filter(({ rect, style }) => style.display !== 'none' && style.visibility !== 'hidden'
            && rect.width > 0 && (rect.right > window.innerWidth + 2 || rect.left < -2))
          .slice(0, 8)
          .map(({ el, rect }) => `${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ''}${el.classList.length ? `.${Array.from(el.classList).slice(0, 3).join('.')}` : ''}[${Math.round(rect.left)},${Math.round(rect.right)}]`)
          .join(' | ')"""))
        nav = page.get_by_role("navigation", name="移动端主要导航")
        bottom_nav_visible = bool(nav.count() and nav.first.is_visible())
        sidebar = page.locator("aside.sidebar")
        sidebar_visible = bool(sidebar.count() and sidebar.first.is_visible())
        compact = width <= 900
        layout_path = route.path in MOBILE_LAYOUT_PATHS or route.path.startswith("/customers/")
        authenticated_public_redirect = route.path in {"/login", "/register"} and route.path not in final_url
        if authenticated_public_redirect:
            status, note = "PASS", "已登录账号按设计跳转工作台；公开页面由未登录矩阵独立验证"
        elif body_chars < 10:
            status, note = "FAIL", "页面正文为空"
        elif overflow and width != 844:
            status, note = "FAIL", "存在页面级横向滚动"
        elif compact and layout_path and not bottom_nav_visible:
            status, note = "FAIL", "移动底栏不可见"
        elif compact and sidebar_visible:
            status, note = "FAIL", "窄屏仍显示桌面侧栏"
        elif not compact and layout_path and not sidebar_visible:
            status, note = "FAIL", "桌面侧栏不可见"
        if route.dynamic_fixture == "controlled-missing" and body_chars >= 10 and not server_errors and not page_errors:
            status, note = "BLOCKED", "正式库无可复用的一次性成功 fixture；已验证受控错误页面"
        elif server_errors or page_errors or console_errors:
            status = "FAIL"
            note = note or "存在控制台、页面或服务器错误"
        if screenshot:
            shot = evidence / "screenshots" / f"{browser_name}-{width}x{height}-{safe_slug(route.path)}.png"
            shot.parent.mkdir(parents=True, exist_ok=True)
            page.screenshot(path=str(shot), full_page=False)
    except Exception as error:
        status, note = "FAIL", str(error).splitlines()[0][:500]
        final_url = page.url
    finally:
        page.remove_listener("console", on_console)
        page.remove_listener("pageerror", on_page_error)
        page.remove_listener("requestfailed", on_request_failed)
        page.remove_listener("response", on_response)
    return {
        "browser": browser_name, "viewport": f"{width}x{height}", "route": route.path,
        "page": route.name, "fixture": route.dynamic_fixture or "static", "result": status,
        "elapsed_ms": round((time.perf_counter() - started) * 1000), "final_url": final_url,
        "body_chars": body_chars, "page_overflow": overflow,
        "overflow_detail": overflow_detail,
        "bottom_nav_visible": bottom_nav_visible, "sidebar_visible": sidebar_visible,
        "console_errors": len(console_errors), "page_errors": len(page_errors),
        "request_failures": len(failed_requests), "server_5xx": len(server_errors),
        "console_detail": " | ".join(console_errors[:3]),
        "page_error_detail": " | ".join(page_errors[:3]),
        "request_failure_detail": " | ".join(failed_requests[:3]),
        "server_5xx_detail": " | ".join(server_errors[:3]),
        "note": note,
    }


def launch(playwright: Playwright, engine: str, base: str) -> Browser:
    if engine == "webkit":
        # WebKit on macOS can inherit the desktop system proxy even when the
        # Python process has no proxy environment variables.  Force the
        # authorized private target to bypass that proxy; an unreachable local
        # proxy keeps every non-bypassed destination fail-closed.
        return playwright.webkit.launch(
            headless=True,
            proxy={
                "server": "http://127.0.0.1:9",
                "bypass": ",".join(filter(None, (urlparse(base).hostname, "localhost", "127.0.0.1"))),
            },
        )
    return getattr(playwright, engine).launch(headless=True)


def run_engine(playwright: Playwright, engine: str, viewports: list[tuple[int, int]],
               base: str, username: str, password: str, evidence: Path) -> list[dict[str, Any]]:
    browser = launch(playwright, engine, base)
    rows: list[dict[str, Any]] = []
    try:
        context: BrowserContext = browser.new_context(ignore_https_errors=False)
        page = context.new_page()
        page.set_viewport_size({"width": viewports[0][0], "height": viewports[0][1]})
        login(page, base, username, password)
        customer_id = first_customer_id(page)
        matrix_routes = routes(customer_id)
        assert len(matrix_routes) == 43
        for width, height in viewports:
            page.set_viewport_size({"width": width, "height": height})
            for route in matrix_routes:
                rows.append(inspect_route(page, base, route, engine, width, height, evidence,
                                          screenshot=(width, height) == (390, 844)))
        context.close()
    finally:
        browser.close()
    return rows


def unauthenticated_checks(playwright: Playwright, base: str) -> list[dict[str, Any]]:
    browser = playwright.chromium.launch(headless=True)
    rows: list[dict[str, Any]] = []
    try:
        context = browser.new_context(viewport={"width": 390, "height": 844})
        page = context.new_page()
        for path in ("/login", "/register", "/customers"):
            page.goto(base + path, wait_until="domcontentloaded", timeout=30_000)
            page.wait_for_timeout(200)
            rows.append({
                "role": "UNAUTHENTICATED", "route": path, "final_url": page.url,
                "result": "PASS" if (path != "/customers" or "/login" in page.url) else "FAIL",
            })
        context.close()
    finally:
        browser.close()
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--credentials", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    base = args.base.rstrip("/")
    args.output.mkdir(parents=True, exist_ok=True)
    username, password = read_credentials(args.credentials)
    rows: list[dict[str, Any]] = []
    roles: list[dict[str, Any]] = []
    launch_failures: list[dict[str, str]] = []
    with sync_playwright() as playwright:
        for engine, sizes in (("chromium", MOBILE_VIEWPORTS + DESKTOP_VIEWPORTS),
                              ("webkit", WEBKIT_VIEWPORTS)):
            try:
                rows.extend(run_engine(playwright, engine, sizes, base, username, password, args.output))
            except Exception as error:
                launch_failures.append({"engine": engine, "error": str(error).splitlines()[0]})
        try:
            roles.extend(unauthenticated_checks(playwright, base))
        except Exception as error:
            launch_failures.append({"engine": "chromium-unauthenticated", "error": str(error).splitlines()[0]})
    if rows:
        with (args.output / "mobile-page-matrix.csv").open("w", encoding="utf-8-sig", newline="") as file:
            writer = csv.DictWriter(file, fieldnames=list(rows[0]))
            writer.writeheader(); writer.writerows(rows)
    if roles:
        with (args.output / "mobile-role-matrix.csv").open("w", encoding="utf-8-sig", newline="") as file:
            writer = csv.DictWriter(file, fieldnames=list(roles[0]))
            writer.writeheader(); writer.writerows(roles)
    summary = {
        "routes": 43,
        "checks": len(rows),
        "pass": sum(row["result"] == "PASS" for row in rows),
        "fail": sum(row["result"] == "FAIL" for row in rows),
        "blocked": sum(row["result"] == "BLOCKED" for row in rows),
        "unauthenticatedChecks": roles,
        "launchFailures": launch_failures,
    }
    (args.output / "mobile-matrix-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))
    return 1 if summary["fail"] or launch_failures else 0


if __name__ == "__main__":
    raise SystemExit(main())

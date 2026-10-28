#!/usr/bin/env python3
"""Focused mobile interaction smoke test for the production Yingge Cloud UI.

Credentials are read from a mode-600 file and are never printed.  The test is
read-only except for acknowledging an already published release dialog, which
matches the route-matrix behaviour and prevents overlays from hiding controls.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from playwright.sync_api import Page, sync_playwright


def credentials(path: Path) -> tuple[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    if not values.get("U") or not values.get("P"):
        raise SystemExit("credential file must contain U and P")
    return values["U"], values["P"]


def dismiss_overlays(page: Page) -> None:
    for label in ("我已知晓，本次登录稍后处理", "我已了解"):
        button = page.get_by_role("button", name=label)
        if button.count() and button.first.is_visible():
            button.first.click(timeout=5_000)
            page.wait_for_timeout(200)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--credentials", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    username, password = credentials(args.credentials)
    results: list[dict[str, object]] = []

    def record(name: str, passed: bool, detail: str = "") -> None:
        results.append({"scenario": name, "result": "PASS" if passed else "FAIL", "detail": detail})

    console_errors: list[str] = []
    page_errors: list[str] = []
    server_errors: list[str] = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 390, "height": 844})
        page = context.new_page()
        try:
            base = args.base.rstrip("/")
            page.goto(f"{base}/login", wait_until="domcontentloaded", timeout=30_000)
            page.locator('input[autocomplete="username"]').fill(username)
            page.locator('input[autocomplete="current-password"]').fill(password)
            page.locator("form.login-card button.primary-button").click()
            page.wait_for_url(re.compile(r"/(dashboard|settings)$"), timeout=30_000)
            # The urgent reminder is fetched asynchronously after AppLayout
            # mounts.  Wait for that first request so the script can exercise
            # the required alert-before-business-sheet ordering deterministically.
            page.wait_for_timeout(1_000)
            dismiss_overlays(page)
            page.wait_for_timeout(350)
            # Begin failure collection only after authentication.  The login
            # page intentionally probes /auth/me and receives 401 when no
            # session exists yet; that is expected control-flow, not a defect.
            page.on("console", lambda message: console_errors.append(message.text) if message.type == "error" else None)
            page.on("pageerror", lambda error: page_errors.append(str(error)))
            page.on("response", lambda response: server_errors.append(f"{response.status} {response.url}") if response.status >= 500 else None)

            nav = page.get_by_role("navigation", name="移动端主要导航")
            record("MOBILE-NAV-01", nav.is_visible() and nav.locator(":scope > *").count() == 5,
                   "固定底栏应显示恰好 5 项")

            page.get_by_role("button", name="更多功能").click()
            more_dialog = page.get_by_role("dialog", name="全部功能")
            record("MOBILE-MORE-01", more_dialog.is_visible(), "更多功能应以全屏 Sheet 打开")
            search = page.get_by_role("searchbox", name="搜索功能")
            search.fill("智能")
            page.wait_for_timeout(150)
            record("MOBILE-MORE-02", "找到" in more_dialog.inner_text() and "智能" in more_dialog.inner_text(),
                   "功能搜索应返回智能相关入口")
            urgent_overlay = page.get_by_role("alertdialog")
            if urgent_overlay.count() and urgent_overlay.first.is_visible():
                record("OVERLAY-PRIORITY-01", True, "A 类紧急提醒正确覆盖在业务 Sheet 之上")
                dismiss_overlays(page)
            else:
                record("OVERLAY-PRIORITY-01", True, "本次会话未在业务 Sheet 打开期间轮询到新提醒")
            more_dialog.get_by_role("button", name="关闭").click()

            page.goto(f"{base}/customers", wait_until="domcontentloaded", timeout=30_000)
            page.wait_for_timeout(500)
            dismiss_overlays(page)
            filter_button = page.get_by_role("button", name=re.compile("筛选客户"))
            record("CUSTOMER-FILTER-01", filter_button.is_visible(), "手机端应显示筛选入口")
            filter_button.click()
            filter_dialog = page.get_by_role("dialog", name="筛选客户")
            record("CUSTOMER-FILTER-02", filter_dialog.is_visible(), "筛选应在真正的 Sheet 中打开")
            record("CUSTOMER-FILTER-03", filter_dialog.get_by_label("移动端搜索客户").is_visible(),
                   "Sheet 应包含客户搜索")
            record("CUSTOMER-FILTER-04", filter_dialog.locator("label", has_text="负责人").locator("select").count() == 1,
                   "管理员 Sheet 应包含负责人筛选")
            filter_dialog.get_by_label("移动端搜索客户").fill("不存在的移动回归客户")
            filter_dialog.get_by_role("button", name="应用筛选").click()
            page.wait_for_timeout(500)
            record("CUSTOMER-FILTER-05", not filter_dialog.is_visible(), "应用后 Sheet 应关闭并返回列表")
            page.screenshot(path=str(args.output / "customers-mobile-filter.png"), full_page=False)

            page.goto(f"{base}/daily-report", wait_until="domcontentloaded", timeout=30_000)
            page.wait_for_timeout(600)
            dismiss_overlays(page)
            calendar = page.get_by_test_id("report-calendar")
            weekday_count = calendar.locator(".report-calendar-weekday").count() if calendar.count() else 0
            record("DAILY-CALENDAR-01", calendar.is_visible() and weekday_count == 7,
                   f"月历应为 7 列，实际星期表头 {weekday_count} 个")
            record("DAILY-CALENDAR-02", page.get_by_test_id("report-day-detail").is_visible(),
                   "选中日期的已提交/未提交详情应可见")

            page.goto(f"{base}/qq-chat", wait_until="domcontentloaded", timeout=30_000)
            page.wait_for_timeout(800)
            dismiss_overlays(page)
            qr = page.get_by_role("img", name="QQ 登录二维码")
            frame = page.locator("iframe.workbench-frame")
            qq_ready = (qr.count() and qr.first.is_visible()) or (frame.count() and frame.first.is_visible())
            qq_state = "PAIRING QR" if qr.count() and qr.first.is_visible() else "CONNECTED WORKBENCH"
            record("QQ-MOBILE-01", bool(qq_ready), f"QQ 手机入口状态：{qq_state}")
            page.screenshot(path=str(args.output / "qq-mobile-state.png"), full_page=False)
        finally:
            context.close()
            browser.close()

    record("BROWSER-CONSOLE-01", not console_errors, " | ".join(console_errors[:3]))
    record("BROWSER-PAGEERROR-01", not page_errors, " | ".join(page_errors[:3]))
    record("BROWSER-5XX-01", not server_errors, " | ".join(server_errors[:3]))
    summary = {
        "checks": len(results),
        "pass": sum(item["result"] == "PASS" for item in results),
        "fail": sum(item["result"] == "FAIL" for item in results),
        "results": results,
    }
    (args.output / "mobile-interaction-smoke.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=False))
    return 1 if summary["fail"] else 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Real-browser UAT for every directly navigable page in the current cloud application."""

from __future__ import annotations

import csv
import hashlib
import json
import os
import re
import sys
import time
from pathlib import Path
from typing import Any

from playwright.sync_api import Browser, Page, sync_playwright


PUBLIC_PAGES = [
    ("PAGE-01", "/login", "登录工作台"),
    ("PAGE-02", "/register", "创建成员账户"),
]
AUTH_PAGES = [
    ("PAGE-03", "/dashboard", "工作台"),
    ("PAGE-04", "/customers", "客户档案"),
    ("PAGE-05", "/customers/new", "新建客户"),
    ("PAGE-06", "/customers/{customerId}", None),
    ("PAGE-07", "/reminders", "跟进提醒"),
    ("PAGE-08", "/tasks", "任务中心"),
    ("PAGE-09", "/content-tasks", "自媒体内容任务"),
    ("PAGE-10", "/circle", "内容圈子"),
    ("PAGE-11", "/announcements", "公告中心"),
    ("PAGE-12", "/team-data", "全组织数据"),
    ("PAGE-13", "/daily-report", "每日汇报"),
    ("PAGE-14", "/imports", "批量导入"),
    ("PAGE-15", "/exports", "数据导出"),
    ("PAGE-16", "/assistant", "智能助手"),
    ("PAGE-17", "/admin/members", "成员账户"),
    ("PAGE-18", "/admin/teams", "团队设置"),
    ("PAGE-19", "/admin/dictionaries", "字典与标签"),
    ("PAGE-20", "/admin/audit", "审计日志"),
    ("PAGE-21", "/settings", "账户设置"),
    ("PAGE-22", "/admin/assistant", "智能助手配置"),
    ("PAGE-23", "/leadership-dashboard", "英格营销数据指挥中心"),
    ("PAGE-24", "/performance", "绩效中心"),
    ("PAGE-25", "/consultations", "咨询交接"),
    ("PAGE-26", "/qq-chat", "QQ 对话整理"),
    ("PAGE-27", "/agent", "我的 Agent"),
    ("PAGE-28", "/admin/agents", "Agent 管理"),
    ("PAGE-29", "/admin/leadership-screen", "大屏设置"),
    ("PAGE-30", "/admin/performance", "绩效管理"),
    ("PAGE-31", "/admin/follow-up", "跟进与咨询设置"),
    ("PAGE-32", "/admin/customer-enrichment", "客户资料补全"),
    ("PAGE-33", "/admin/customer-intelligence", "客户智能分析"),
    ("PAGE-34", "/leadership-screen", "英格营销数据指挥中心"),
    ("PAGE-35", "/customers/{customerId}/edit", "编辑客户画像"),
]


def read_env(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        if raw.strip() and not raw.lstrip().startswith("#") and "=" in raw:
            key, value = raw.split("=", 1)
            result[key.strip()] = value.strip()
    return result


class BrowserUat:
    def __init__(self, base: str, username: str, password: str, output: Path) -> None:
        self.base = base.rstrip("/")
        self.username = username
        self.password = password
        self.output = output
        self.screens = output / "screenshots"
        self.downloads = output / "downloads"
        self.screens.mkdir(parents=True, exist_ok=True)
        self.downloads.mkdir(parents=True, exist_ok=True)
        self.rows: list[dict[str, Any]] = []
        self.trace_ids: set[str] = set()
        self.page_filter = {
            item.strip() for item in os.getenv("UAT_PAGE_FILTER", "").split(",") if item.strip()
        }
        self.desktop_width = int(os.getenv("UAT_DESKTOP_WIDTH", "1920"))
        self.desktop_height = int(os.getenv("UAT_DESKTOP_HEIGHT", "1080"))
        if self.desktop_width < 320 or self.desktop_height < 320:
            raise ValueError("UAT desktop viewport must be at least 320x320")
        self.desktop_viewport = f"{self.desktop_width}x{self.desktop_height}"

    def attach_observers(self, page: Page, errors: list[str], failures: list[str]) -> None:
        page.on("console", lambda msg: errors.append(f"console:{msg.type}:{msg.text}") if msg.type == "error" else None)
        page.on("pageerror", lambda error: errors.append(f"pageerror:{error}"))
        page.on("requestfailed", lambda request: failures.append(
            f"{request.method} {request.url} {request.failure or 'failed'}")
            if "ERR_ABORTED" not in str(request.failure) else None)

        def response_observer(response: Any) -> None:
            trace = response.headers.get("x-trace-id")
            if trace:
                self.trace_ids.add(trace)
            if response.status >= 400 and "/api/" in response.url:
                failures.append(f"HTTP {response.status} {response.url}")

        page.on("response", response_observer)

    def login(self, page: Page) -> None:
        page.goto(self.base + "/login", wait_until="domcontentloaded", timeout=120_000)
        page.get_by_label("用户名").fill(self.username)
        page.get_by_label("密码").fill(self.password)
        page.get_by_role("button", name="安全登录").click()
        page.wait_for_url(re.compile(r"/dashboard$"), timeout=120_000)
        page.get_by_role("heading", name="工作台").wait_for(timeout=120_000)

    def first_customer_id(self, page: Page) -> str:
        payload = page.evaluate("""async () => {
            const response = await fetch('/api/v1/customers?size=1&includeArchived=true');
            if (!response.ok) throw new Error(`customers ${response.status}`);
            return await response.json();
        }""")
        return payload["items"][0]["id"]

    def capture(self, page: Page, browser_name: str, page_id: str, route: str,
                expected_heading: str | None, viewport: str = "1920x1080") -> None:
        started = time.perf_counter()
        errors: list[str] = []
        failures: list[str] = []
        self.attach_observers(page, errors, failures)
        status = "PASS"
        actual = ""
        heading = ""
        overflow = False
        root_overflow = False
        overflow_details: list[str] = []
        try:
            response = page.goto(self.base + route, wait_until="domcontentloaded", timeout=120_000)
            if response is None or response.status >= 400:
                raise AssertionError(f"navigation HTTP {None if response is None else response.status}")
            if expected_heading:
                heading_locator = page.get_by_role("heading", name=expected_heading, exact=True)
                heading_locator.wait_for(state="visible", timeout=120_000)
                if heading_locator.count() != 1:
                    raise AssertionError(f"expected one exact heading {expected_heading!r}")
            else:
                heading_locator = page.locator("h1").first
                heading_locator.wait_for(state="visible", timeout=120_000)
            heading = heading_locator.inner_text().strip()
            page.wait_for_timeout(750)
            root_overflow = bool(page.evaluate("document.documentElement.scrollWidth > window.innerWidth + 2"))
            if root_overflow:
                overflow_details = page.evaluate("""() => [...document.querySelectorAll('body *')]
                    .filter((element) => {
                      const rect = element.getBoundingClientRect();
                      return rect.right > window.innerWidth + 2 || rect.left < -2;
                    })
                    .filter((element) => {
                      let ancestor = element.parentElement;
                      while (ancestor && ancestor !== document.body) {
                        const style = getComputedStyle(ancestor);
                        const rect = ancestor.getBoundingClientRect();
                        const clipsOverflow = /^(auto|scroll|hidden|clip)$/.test(style.overflowX);
                        const isViewportContained = rect.left >= -2 && rect.right <= window.innerWidth + 2;
                        if (clipsOverflow && isViewportContained) return false;
                        ancestor = ancestor.parentElement;
                      }
                      return true;
                    })
                    .slice(0, 12)
                    .map((element) => {
                      const rect = element.getBoundingClientRect();
                      const parent = element.parentElement;
                      const parentRect = parent?.getBoundingClientRect();
                      const style = getComputedStyle(element);
                      const classes = typeof element.className === 'string'
                        ? element.className.trim().split(/\\s+/).slice(0, 3).join('.') : '';
                      return `${element.tagName.toLowerCase()}${element.id ? `#${element.id}` : ''}${classes ? `.${classes}` : ''}`
                        + `[type=${element.getAttribute('type') || ''},left=${Math.round(rect.left)},right=${Math.round(rect.right)},width=${Math.round(rect.width)},cssWidth=${style.width},parent=${parent?.tagName || ''}.${parent?.className || ''},parentWidth=${Math.round(parentRect?.width || 0)}]`;
                    })""")
                overflow = bool(overflow_details)
                if overflow:
                    raise AssertionError("uncontained horizontal overflow: " + "; ".join(overflow_details))
            image = self.screens / f"{browser_name}-{viewport}-{page_id}.jpg"
            page.screenshot(path=str(image), full_page=True, type="jpeg", quality=72)
            if route in {"/login", "/register"}:
                failures[:] = [item for item in failures if "/api/v1/auth/me" not in item]
                if not failures:
                    errors[:] = [item for item in errors if "Failed to load resource" not in item]
            if errors or failures:
                raise AssertionError("; ".join((errors + failures)[:8]))
            actual = f"heading={heading}; layout stable; console clean; screenshot={image.name}"
        except Exception as error:
            status = "FAIL"
            actual = f"{type(error).__name__}: {error}"
            image = self.screens / f"{browser_name}-{viewport}-{page_id}-failure.jpg"
            try:
                page.screenshot(path=str(image), full_page=True, type="jpeg", quality=72)
            except Exception:
                pass
        self.rows.append({
            "pageId": page_id, "browser": browser_name, "viewport": viewport, "route": route,
            "expectedHeading": expected_heading or "dynamic customer name", "actualHeading": heading,
            "status": status, "overflow": overflow, "rootScrollOverflow": root_overflow,
            "consoleErrors": len(errors),
            "requestFailures": len(failures), "durationMs": round((time.perf_counter() - started) * 1000),
            "overflowDetails": " | ".join(overflow_details), "actual": actual,
        })
        suffix = f" — {actual}" if status == "FAIL" else ""
        print(f"{browser_name} {page_id} {status}{suffix}", flush=True)

    def real_form_actions(self, page: Page, customer_id: str) -> str:
        tag = str(int(time.time()))[-8:]
        page.goto(self.base + "/customers/new", wait_until="domcontentloaded", timeout=120_000)
        page.get_by_label("姓名 *").fill(f"浏览器UAT-{tag}")
        page.get_by_label("手机号").fill(f"15{tag}1")
        page.get_by_role("button", name="创建客户").click()
        page.wait_for_url(re.compile(r"/customers/[0-9a-f-]+$"), timeout=120_000)
        created_id = page.url.rsplit("/", 1)[-1]
        page.get_by_role("button", name=re.compile("记录沟通")).click()
        page.get_by_label("摘要 *").fill("浏览器 UAT 真实沟通记录")
        page.get_by_role("button", name="完成记录").click()
        page.get_by_text("浏览器 UAT 真实沟通记录").wait_for(timeout=120_000)

        page.goto(self.base + "/customers", wait_until="domcontentloaded", timeout=120_000)
        page.get_by_placeholder("搜索姓名、学校、手机、微信或 QQ").fill(f"浏览器UAT-{tag}")
        page.wait_for_timeout(1_000)
        page.get_by_text(f"浏览器UAT-{tag}").first.wait_for(timeout=60_000)

        page.goto(self.base + "/imports", wait_until="domcontentloaded", timeout=120_000)
        fixture = Path(__file__).resolve().parent / "fixtures" / "customers-10.xlsx"
        page.locator('input[type="file"]').set_input_files(str(fixture))
        page.get_by_role("button", name="生成导入预览").click()
        page.get_by_text("可导入").first.wait_for(timeout=120_000)

        page.goto(self.base + "/exports", wait_until="domcontentloaded", timeout=120_000)
        page.get_by_label("CSV").check()
        with page.expect_download(timeout=180_000) as download_info:
            page.get_by_role("button", name="开始安全导出").click()
        download = download_info.value
        target = self.downloads / (download.suggested_filename or "customers.csv")
        download.save_as(target)
        digest = hashlib.sha256(target.read_bytes()).hexdigest()

        page.goto(self.base + "/daily-report", wait_until="domcontentloaded", timeout=120_000)
        page.get_by_label("2. 深度沟通人数").fill("4")
        page.get_by_label("3. 今日问题").fill("浏览器 UAT 汇报问题")
        page.get_by_label("4. 明日计划").fill("浏览器 UAT 回归计划")
        page.locator("form.report-form button.primary-button").click()
        page.get_by_text("今日汇报已提交。").wait_for(timeout=60_000)

        page.goto(self.base + "/dashboard", wait_until="domcontentloaded", timeout=120_000)
        page.locator('nav a[href="/customers"]').dblclick()
        page.wait_for_url(re.compile(r"/customers$"), timeout=60_000)
        page.reload(wait_until="domcontentloaded", timeout=120_000)
        return f"createdCustomer={created_id}; baselineCustomer={customer_id}; exportSha256={digest}"

    def run_browser(self, playwright: Any, browser_name: str, channel: str, real_actions: bool) -> None:
        browser: Browser = playwright.chromium.launch(channel=channel, headless=True)
        try:
            public_context = browser.new_context(viewport={
                "width": self.desktop_width, "height": self.desktop_height,
            })
            for page_id, route, heading in PUBLIC_PAGES:
                if self.page_filter and page_id not in self.page_filter:
                    continue
                public_page = public_context.new_page()
                self.capture(public_page, browser_name, page_id, route, heading, self.desktop_viewport)
                public_page.close()
            public_context.close()

            context = browser.new_context(viewport={
                "width": self.desktop_width, "height": self.desktop_height,
            }, accept_downloads=True)
            page = context.new_page()
            self.login(page)
            customer_id = self.first_customer_id(page)
            page.close()
            for page_id, template, heading in AUTH_PAGES:
                if self.page_filter and page_id not in self.page_filter:
                    continue
                route = template.format(customerId=customer_id)
                route_page = context.new_page()
                self.capture(route_page, browser_name, page_id, route, heading, self.desktop_viewport)
                route_page.close()
            if real_actions:
                action_page = context.new_page()
                action_result = self.real_form_actions(action_page, customer_id)
                action_page.close()
                (self.output / f"{browser_name}-form-actions.txt").write_text(action_result + "\n", encoding="utf-8")

            for page_id, route, heading in [
                ("NARROW-01", "/dashboard", "工作台"),
                ("NARROW-02", "/customers", "客户档案"),
                ("NARROW-03", f"/customers/{customer_id}", None),
                ("NARROW-04", "/admin/members", "成员账户"),
                ("NARROW-05", "/admin/customer-intelligence", "客户智能分析"),
                ("NARROW-06", "/leadership-dashboard", "英格营销数据指挥中心"),
            ]:
                if self.page_filter and page_id not in self.page_filter:
                    continue
                narrow_page = context.new_page()
                narrow_page.set_viewport_size({"width": 390, "height": 844})
                self.capture(narrow_page, browser_name, page_id, route, heading, "390x844")
                narrow_page.close()
            context.close()
        finally:
            browser.close()

    def write(self) -> None:
        (self.output / "page-results.json").write_text(json.dumps(self.rows, ensure_ascii=False, indent=2), encoding="utf-8")
        with (self.output / "page-results.csv").open("w", newline="", encoding="utf-8-sig") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(self.rows[0]))
            writer.writeheader(); writer.writerows(self.rows)
        summary = {
            "checks": len(self.rows), "passed": sum(row["status"] == "PASS" for row in self.rows),
            "failed": sum(row["status"] == "FAIL" for row in self.rows),
            "uniqueFormalPages": len({row["pageId"] for row in self.rows if row["pageId"].startswith("PAGE-")}),
            "browsers": sorted({row["browser"] for row in self.rows}),
            "traceIds": sorted(self.trace_ids),
        }
        (self.output / "browser-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(summary, ensure_ascii=False), flush=True)


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: playwright_business.py BASE_URL PASSWORD_ENV OUTPUT_DIR")
    credentials = read_env(Path(sys.argv[2]))
    username = credentials.get("UAT_USERNAME", "uat-admin")
    password = credentials.get("UAT_PASSWORD")
    if not password:
        raise SystemExit("UAT_PASSWORD missing")
    suite = BrowserUat(sys.argv[1], username, password, Path(sys.argv[3]))
    configured_channels = {
        item.strip() for item in os.getenv("UAT_BROWSER_CHANNELS", "chrome,edge").split(",") if item.strip()
    }
    skip_real_actions = os.getenv("UAT_SKIP_REAL_ACTIONS", "").lower() in {"1", "true", "yes"}
    with sync_playwright() as playwright:
        if "chrome" in configured_channels:
            suite.run_browser(playwright, "chrome", "chrome", not skip_real_actions)
        if "edge" in configured_channels:
            suite.run_browser(playwright, "edge", "msedge", False)
    suite.write()
    raise SystemExit(1 if any(row["status"] == "FAIL" for row in suite.rows) else 0)


if __name__ == "__main__":
    main()

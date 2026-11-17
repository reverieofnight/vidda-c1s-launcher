#!/usr/bin/env python3
"""用无头浏览器给「远程导入」网页截图 + 打印卡片按钮状态。

依赖（一次性，装在用户目录，不进仓库）：
  export LD_LIBRARY_PATH=/tmp/pwlibs/usr/lib/x86_64-linux-gnu   # 见 项目说明.md 环境坑
  python3 -m pip install --user --break-system-packages -i https://pypi.tuna.tsinghua.edu.cn/simple playwright
  PLAYWRIGHT_DOWNLOAD_HOST=https://cdn.npmmirror.com/binaries/playwright python3 -m playwright install chromium

用法：python3 tools/ui-shot.py [URL] [输出目录]
"""
import sys, json, pathlib
from playwright.sync_api import sync_playwright

url = sys.argv[1] if len(sys.argv) > 1 else "http://192.168.2.19:8080/"
out = pathlib.Path(sys.argv[2] if len(sys.argv) > 2 else "local/screens/ui")
out.mkdir(parents=True, exist_ok=True)

with sync_playwright() as p:
    b = p.chromium.launch()
    for tag, vp, mobile in [("desktop", {"width": 1280, "height": 1000}, False),
                            ("phone", {"width": 390, "height": 844}, True)]:
        pg = b.new_page(viewport=vp, device_scale_factor=2 if mobile else 1, is_mobile=mobile)
        pg.goto(url, wait_until="networkidle")
        pg.wait_for_timeout(1200)
        path = out / f"远程导入页-{tag}.png"
        pg.screenshot(path=str(path), full_page=(tag == "desktop"))
        if tag == "desktop":
            rows = pg.evaluate("""() => [...document.querySelectorAll('.item')].map(c => {
                const btn = c.querySelector('.ops button');
                return { name: c.querySelector('.name').textContent.trim(),
                         cur: c.classList.contains('cur'), text: btn.textContent,
                         disabled: btn.disabled, cls: btn.className };
            })""")
            print(json.dumps(rows, ensure_ascii=False, indent=2))
        print("saved", path)
        pg.close()
    b.close()

from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing anchor: {label}")
    return text.replace(old, new, 1)

p = Path("app/src/main/java/kr/co/zillocr/overlay/capture/ScreenOcrService.kt")
s = p.read_text(encoding="utf-8")
s = replace_once(s, "import android.widget.LinearLayout\nimport android.widget.TextView\n", "import android.widget.LinearLayout\nimport android.widget.ScrollView\nimport android.widget.TextView\n", "ScrollView import")
s = replace_once(s, "    private var detailsPanel: LinearLayout? = null\n", "    private var detailsPanel: View? = null\n", "detailsPanel type")
s = replace_once(s, '        actions.addView(primaryButton("숨") { toggleResultVisibility() })\n        actions.addView(primaryButton("재") { retryLastTranslation() })\n', '        actions.addView(primaryButton("숨") { toggleResultVisibility() })\n        actions.addView(primaryButton("재") { retryLastTranslation() })\n        actions.addView(primaryButton("영역") {\n            detailsPanel?.visibility = View.GONE\n            beginRegionSelection()\n        })\n', "primary region button")
old = '''        val panelWidth = minOf(dp(240), (screenWidth - dp(16)).coerceAtLeast(dp(190)))\n        val panel = LinearLayout(this).apply {\n            orientation = LinearLayout.VERTICAL\n            setBackgroundColor(0xF21C1C22.toInt())\n            setPadding(dp(10), dp(10), dp(10), dp(10))\n            visibility = View.GONE\n            layoutParams = LinearLayout.LayoutParams(panelWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }\n        }\n        detailsPanel = panel\n'''
new = '''        val panelWidth = minOf(dp(240), (screenWidth - dp(16)).coerceAtLeast(dp(190)))\n        val panelMaxHeight = (screenHeight - dp(110)).coerceAtLeast(dp(160))\n        val panel = LinearLayout(this).apply {\n            orientation = LinearLayout.VERTICAL\n            setBackgroundColor(0xF21C1C22.toInt())\n            setPadding(dp(10), dp(10), dp(10), dp(10))\n        }\n        val panelScroll = ScrollView(this).apply {\n            isFillViewport = false\n            visibility = View.GONE\n            addView(panel, ScrollView.LayoutParams(panelWidth, ScrollView.LayoutParams.WRAP_CONTENT))\n            layoutParams = LinearLayout.LayoutParams(panelWidth, panelMaxHeight).apply { topMargin = dp(6) }\n        }\n        detailsPanel = panelScroll\n'''
s = replace_once(s, old, new, "scrollable panel")
s = replace_once(s, '        panel.addView(panelButton("OCR 영역 다시 지정") { beginRegionSelection(); panel.visibility = View.GONE })\n', '        panel.addView(panelButton("OCR 영역 다시 지정") { beginRegionSelection(); panelScroll.visibility = View.GONE })\n', "panel region hide")
s = replace_once(s, "        root.addView(panel)\n", "        root.addView(panelScroll)\n", "panel root")
p.write_text(s, encoding="utf-8")

p = Path("app/build.gradle.kts")
s = p.read_text(encoding="utf-8")
s = replace_once(s, "versionCode = 22", "versionCode = 23", "versionCode")
s = replace_once(s, 'versionName = "0.5.0-alpha8.1"', 'versionName = "0.5.0-alpha8.2"', "versionName")
p.write_text(s, encoding="utf-8")

p = Path("app/src/main/java/kr/co/zillocr/overlay/MainActivity.kt")
s = p.read_text(encoding="utf-8")
s = replace_once(s, "질올 실시간 번역 오버레이 · 0.5.0 alpha8.1", "질올 실시간 번역 오버레이 · 0.5.0 alpha8.2", "visible version")
p.write_text(s, encoding="utf-8")

print("alpha8.2 UI patch applied")

from pathlib import Path
p = Path('app/src/main/java/kr/co/zillocr/overlay/capture/ScreenOcrService.kt')
s = p.read_text(encoding='utf-8')
old = 'addView(panel, ScrollView.LayoutParams(panelWidth, ScrollView.LayoutParams.WRAP_CONTENT))'
new = 'addView(panel, FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT))'
if old not in s:
    raise SystemExit('scroll layout anchor missing')
p.write_text(s.replace(old, new, 1), encoding='utf-8')
print('fixed scroll layout params')

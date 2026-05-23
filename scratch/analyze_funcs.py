import sys
file_path = "app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerActivity.kt"

with open(file_path, "r", encoding="utf-8") as f:
    lines = f.readlines()

print(f"Total lines: {len(lines)}")

current_func = None
start_line = 0

for i, line in enumerate(lines):
    line_num = i + 1
    stripped = line.strip()
    if stripped.startswith("fun ") or stripped.startswith("private fun ") or stripped.startswith("override fun ") or stripped.startswith("protected fun ") or stripped.startswith("internal fun "):
        if current_func:
            print(f"{current_func} (lines {start_line}-{line_num-1}): {line_num - start_line} lines")
        current_func = stripped.split("(")[0]
        start_line = line_num

if current_func:
    print(f"{current_func} (lines {start_line}-{len(lines)}): {len(lines) - start_line + 1} lines")

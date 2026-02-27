import os
import re

dir_path = r"app\src\main\java\com\tvonnet\debridxtreamiptv"

files_to_check = []
for root, _, files in os.walk(dir_path):
    for f in files:
        if f.endswith(".kt"):
            files_to_check.append(os.path.join(root, f))

# Pattern to find `.load(XXX)`
# We want to replace it IF XXX is NOT already wrapped in GlobalConfig.resolveIconUrl
# and XXX might be `movie.stream_icon`, `vod.cover`, `stream.stream_icon`, etc.

for filepath in files_to_check:
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    # Manual replacements for common variable assignments instead of just Glide.load
    # For example: val posterUrl = movie.stream_icon
    # We replace: = movie.stream_icon  ->  = GlobalConfig.resolveIconUrl(movie.stream_icon)
    
    replacements = [
        (r"=\s*([a-zA-Z0-9_\?]+)\.stream_icon(?!.*\))", r"= com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(\1.stream_icon)"),
        (r"=\s*([a-zA-Z0-9_\?]+)\.cover(?!.*\))", r"= com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(\1.cover)"),
        (r"\.load\(\s*([a-zA-Z0-9_\?]+)\.stream_icon\s*\)", r".load(com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(\1.stream_icon))"),
        (r"\.load\(\s*([a-zA-Z0-9_\?]+)\.cover\s*\)", r".load(com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(\1.cover))"),
        # also handle `channel.stream_icon` inside addFavorite
        (r"repository\.addFavorite\((.*?), (.*?), (.*?), channel\.stream_icon\)", r"repository.addFavorite(\1, \2, \3, com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(channel.stream_icon))")
    ]
    
    new_content = content
    for old, new in replacements:
        # Avoid double wrapping
        if "resolveIconUrl" not in new_content:
            new_content = re.sub(old, new, new_content)
    
    # Also handle specific fallback cases like `vod.cover ?: vod.stream_icon`
    new_content = re.sub(r"=\s*([a-zA-Z0-9_\?]+)\.cover\s*\?:\s*\1\.stream_icon", r"= com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(\1.cover) ?: com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(\1.stream_icon)", new_content)

    if new_content != content:
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(new_content)
        print(f"Patched {filepath}")

print("Done")

import os
import re

def wrap_with_layout(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    
    if '<layout' in content:
        print(f"Already wrapped: {filepath}")
        return

    # Find the XML declaration
    xml_decl_match = re.search(r'<\?xml.*?\?>\n*', content)
    if xml_decl_match:
        xml_decl = xml_decl_match.group(0)
        content_no_decl = content[xml_decl_match.end():]
    else:
        xml_decl = '<?xml version="1.0" encoding="utf-8"?>\n'
        content_no_decl = content

    new_content = xml_decl + '<layout xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:app="http://schemas.android.com/apk/res-auto"\n    xmlns:tools="http://schemas.android.com/tools">\n\n    <data>\n        <!-- Variables go here if needed -->\n    </data>\n\n    '
    
    # Remove xmlns from the root element of content_no_decl to avoid duplication
    content_no_decl = re.sub(r'\s*xmlns:android="[^"]*"', '', content_no_decl)
    content_no_decl = re.sub(r'\s*xmlns:app="[^"]*"', '', content_no_decl)
    content_no_decl = re.sub(r'\s*xmlns:tools="[^"]*"', '', content_no_decl)
    
    # Indent the rest of the content
    indented_content = '\n    '.join(content_no_decl.split('\n'))
    
    new_content += indented_content + '\n</layout>\n'
    
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(new_content)
    print(f"Wrapped {filepath} in <layout>")

base_dir = r"e:\running project baxkups\lmsd working but loading issues\debxtrem\app\src\main\res\layout"

# 1. Wrap item_cast_member.xml
wrap_with_layout(os.path.join(base_dir, 'item_cast_member.xml'))

# 2. Wrap item_filter_chip.xml
wrap_with_layout(os.path.join(base_dir, 'item_filter_chip.xml'))

# 3. Fix and wrap item_movie_source.xml
movie_source_path = os.path.join(base_dir, 'item_movie_source.xml')
with open(movie_source_path, 'r', encoding='utf-8') as f:
    ms_content = f.read()

# Fix IDs
ms_content = ms_content.replace('android:id="@+id/tv_provider_badge"', 'android:id="@+id/tv_provider"')
ms_content = ms_content.replace('android:id="@+id/tv_source_label"', 'android:id="@+id/tv_release_name"')
ms_content = ms_content.replace('android:id="@+id/tv_badge_quality"', 'android:id="@+id/tv_quality"')
ms_content = ms_content.replace('android:id="@+id/tv_badge_cached"', 'android:id="@+id/tv_type"')
ms_content = ms_content.replace('android:id="@+id/tv_badge_size"', 'android:id="@+id/tv_filesize"')
ms_content = ms_content.replace('android:id="@+id/iv_play_icon"', 'android:id="@+id/iv_play"')

# Fix ll_languages: it is currently a TextView, we must convert it to a LinearLayout so addView works.
# The original has:
# <TextView
#     android:id="@+id/tv_language_flag"
#     android:layout_width="250dp"
#     android:layout_height="wrap_content"
#     android:layout_marginStart="14dp"
#     android:textColor="#94A3B8"
#     android:textSize="16sp"
#     android:gravity="center_vertical"
#     app:layout_constraintStart_toEndOf="@id/tv_provider_badge"
#     app:layout_constraintTop_toTopOf="parent"
#     app:layout_constraintBottom_toBottomOf="parent"
#     tools:text="🇺🇸 EN" />
# We replace it with:
replacement_lang = """<LinearLayout
        android:id="@+id/ll_languages"
        android:layout_width="250dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="14dp"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        app:layout_constraintStart_toEndOf="@id/tv_provider"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent" />"""

ms_content = re.sub(r'<TextView\s+android:id="@+id/tv_language_flag".*?/>', replacement_lang, ms_content, flags=re.DOTALL)

# Fix references to tv_language_flag to ll_languages
ms_content = ms_content.replace('@id/tv_language_flag', '@id/ll_languages')
ms_content = ms_content.replace('@id/tv_provider_badge', '@id/tv_provider')

# Write back
with open(movie_source_path, 'w', encoding='utf-8') as f:
    f.write(ms_content)

# Now wrap it
wrap_with_layout(movie_source_path)

print("Finished fixing layouts.")

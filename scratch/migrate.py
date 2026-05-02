import os

root = 'D:/cursor working/debxtrem/app/src/main/java'
classes = [
    'PlayerViewModel',
    'PlayerActivity',
    'PlayerOverlayUiState',
    'ZapOverlayState',
    'ZapChannelInfo',
    'SeriesPlaylistQueueState',
    'PlaybackSource',
    'BrowserUiState',
    'DebridResolutionState',
    'BrowserCategoryAdapter',
    'BrowserChannelAdapter'
]

def migrate():
    for r, d, fs in os.walk(root):
        # Skip the stabilized directory itself to avoid double-prefixing or changing the package declaration there
        if 'player/stabilized' in r.replace('\\', '/'):
            continue
            
        for f in fs:
            if f.endswith('.kt') or f.endswith('.java'):
                p = os.path.join(r, f)
                try:
                    with open(p, 'r', encoding='utf-8') as f_in:
                        content = f_in.read()
                    
                    new_content = content
                    for c in classes:
                        # 1. Replace explicit long imports
                        new_content = new_content.replace('import com.tvonnet.debridxtreamiptv.player.' + c, 'import com.tvonnet.debridxtreamiptv.player.stabilized.' + c)
                        # 2. Support subpackages (e.g. .v2.)
                        new_content = new_content.replace('import com.tvonnet.debridxtreamiptv.player.v2.' + c, 'import com.tvonnet.debridxtreamiptv.player.stabilized.' + c)
                        # 3. Handle cases where the class is referenced fully qualified in code
                        new_content = new_content.replace('com.tvonnet.debridxtreamiptv.player.' + c, 'com.tvonnet.debridxtreamiptv.player.stabilized.' + c)
                    
                    if new_content != content:
                        with open(p, 'w', encoding='utf-8') as f_out:
                            f_out.write(new_content)
                        print(f"Updated: {f}")
                except Exception as e:
                    print(f"Error processing {f}: {e}")

if __name__ == '__main__':
    migrate()

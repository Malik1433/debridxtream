import sqlite3
conn = sqlite3.connect('db.sqlite')
c = conn.cursor()
c.execute("SELECT identity_key, content_type, source, is_watched, series_id, season_number, episode_number, episode_id FROM watched_state WHERE content_type='EPISODE'")
rows = c.fetchall()
print(f"EPISODE rows found: {len(rows)}")
for row in rows:
    print(row)

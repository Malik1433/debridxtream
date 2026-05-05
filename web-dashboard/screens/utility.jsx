/* global React, DATA */

function SearchScreen() {
  const [q, setQ] = React.useState("Crims");
  const results = DATA.TRENDING.concat(DATA.NEW_ARRIVALS).slice(0, 12);
  const recents = ["Foundation","Crimson Horizon","Top Gun","Bridgerton","Premier League"];
  return (
    <div style={{ display: "flex", height: "100%", background: "var(--bg-deepest)" }}>
      <Sidebar active="search" />
      <main style={{ flex: 1, position: "relative", overflow: "hidden", display: "flex", flexDirection: "column" }}>
        <AmbientBloom />
        <div style={{ position: "relative", zIndex: 2, padding: "44px 48px 0" }}>
          <div className="t-eyebrow">Search</div>
          <div className="glass-strong" style={{
            marginTop: 14, padding: "18px 24px", borderRadius: 18,
            display: "flex", alignItems: "center", gap: 16,
            border: "1px solid rgba(212,175,55,.4)", boxShadow: "var(--shadow-bloom-soft)",
          }}>
            <Icon name="search" size={26} color="var(--gold-300)" />
            <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search movies, series, channels…" style={{
              flex: 1, background: "transparent", border: "none", outline: "none",
              color: "var(--ink-100)", fontSize: 24, fontWeight: 600, fontFamily: "inherit",
            }} />
            <button className="btn btn-secondary" style={{ width: 44, height: 44, padding: 0, borderRadius: "50%" }}><Icon name="close" size={16} /></button>
          </div>
          <div style={{ display: "flex", gap: 10, marginTop: 18, flexWrap: "wrap" }}>
            <span style={{ fontSize: 13, color: "var(--ink-400)", marginRight: 4, alignSelf: "center" }}>Recent:</span>
            {recents.map(r => <span key={r} className="pill" style={{ cursor: "pointer" }}>{r}</span>)}
          </div>
        </div>
        <div style={{ padding: "32px 48px 48px", flex: 1, overflowY: "auto", position: "relative", zIndex: 2 }}>
          <div className="section-header"><h3 className="section-title">{results.length} results for "{q}"</h3>
            <div style={{ display: "flex", gap: 8 }}>
              <span className="pill" style={{ borderColor: "var(--gold-500)", color: "var(--gold-300)" }}>All</span>
              <span className="pill">Movies</span>
              <span className="pill">Series</span>
              <span className="pill">Channels</span>
            </div>
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(6, 1fr)", gap: 22 }}>
            {results.map((m, i) => <Poster key={"sr-" + i} {...m} id={"sr-" + i} h={300} />)}
          </div>
        </div>
      </main>
    </div>
  );
}

/* ============= SETTINGS ============= */
function SettingsScreen() {
  const cats = [
    { id: "account", label: "Account", icon: "user" },
    { id: "playback", label: "Playback", icon: "play" },
    { id: "providers", label: "Providers & Debrid", icon: "globe" },
    { id: "appearance", label: "Appearance", icon: "film" },
    { id: "parental", label: "Parental", icon: "shield" },
    { id: "device", label: "Device", icon: "cast" },
    { id: "about", label: "About", icon: "info" },
  ];
  const [active, setActive] = React.useState("playback");
  return (
    <div style={{ display: "flex", height: "100%", background: "var(--bg-deepest)" }}>
      <Sidebar active="settings" />
      <main style={{ flex: 1, display: "grid", gridTemplateColumns: "300px 1fr", overflow: "hidden", position: "relative" }}>
        <AmbientBloom />
        <div style={{
          padding: "44px 16px", borderRight: "1px solid rgba(255,255,255,.05)",
          background: "rgba(11,14,21,.5)", position: "relative", zIndex: 2,
        }}>
          <div style={{ padding: "0 12px 20px" }}>
            <div className="t-eyebrow">Settings</div>
            <div style={{ fontSize: 22, fontWeight: 700, marginTop: 6 }}>Preferences</div>
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
            {cats.map(c => (
              <button key={c.id} onClick={() => setActive(c.id)} style={{
                display: "flex", alignItems: "center", gap: 12, padding: "12px 16px", borderRadius: 12, cursor: "pointer",
                border: "1px solid " + (active === c.id ? "rgba(212,175,55,.35)" : "transparent"),
                background: active === c.id ? "linear-gradient(90deg, rgba(212,175,55,.16), transparent 80%)" : "transparent",
                color: active === c.id ? "var(--ink-100)" : "var(--ink-300)",
                fontSize: 14, fontWeight: active === c.id ? 700 : 500, textAlign: "left",
              }}>
                <Icon name={c.icon} size={18} color={active === c.id ? "var(--gold-300)" : "currentColor"} />
                <span>{c.label}</span>
              </button>
            ))}
          </div>
        </div>
        <div style={{ padding: "44px 48px", overflowY: "auto", position: "relative", zIndex: 2 }}>
          <div className="t-eyebrow">Playback</div>
          <h2 style={{ margin: "8px 0 24px", fontSize: 32, fontWeight: 800, letterSpacing: "-0.01em" }}>Playback &amp; Quality</h2>
          <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
            <SettingRow label="Default Quality" sub="Pick the highest stream available within your bandwidth" value="4K HDR" />
            <SettingRow label="Auto‑play next episode" sub="Start the next episode 8 seconds before the credits" toggle on />
            <SettingRow label="Hardware acceleration" sub="Use device codec where available" toggle on />
            <SettingRow label="Subtitle language" sub="Falls back to English when unavailable" value="English" />
            <SettingRow label="Audio language" sub="Original audio when available" value="Original" />
            <SettingRow label="Skip intros automatically" sub="Detected via fingerprint" toggle />
            <SettingRow label="Buffer size" sub="Higher uses more RAM" value="Balanced (60s)" />
          </div>
          <div style={{ marginTop: 36 }}>
            <h3 className="section-title">Cache &amp; storage</h3>
            <div className="glass" style={{ marginTop: 16, padding: 22, borderRadius: 16 }}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", marginBottom: 10 }}>
                <span style={{ fontWeight: 600 }}>Used 4.2 GB of 16 GB</span>
                <span style={{ color: "var(--ink-400)", fontSize: 13 }}>Last cleaned 3 days ago</span>
              </div>
              <div style={{ height: 8, borderRadius: 4, background: "rgba(255,255,255,.08)", overflow: "hidden" }}>
                <div style={{ width: "26%", height: "100%", background: "linear-gradient(90deg, #F0CB57, #D4AF37)", boxShadow: "0 0 10px rgba(212,175,55,.5)" }} />
              </div>
              <div style={{ display: "flex", gap: 10, marginTop: 16 }}>
                <button className="btn btn-secondary">Clear cache</button>
                <button className="btn btn-ghost">Manage downloads</button>
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}

function SettingRow({ label, sub, value, toggle, on }) {
  const { isFocused, bind } = useFocusable("set-" + label, true);
  return (
    <div {...bind} style={{
      display: "flex", alignItems: "center", justifyContent: "space-between", gap: 18,
      padding: 18, borderRadius: 14,
      background: isFocused ? "linear-gradient(90deg, rgba(212,175,55,.10), transparent 80%)" : "rgba(255,255,255,.03)",
      border: "1px solid " + (isFocused ? "rgba(212,175,55,.4)" : "rgba(255,255,255,.06)"),
      boxShadow: isFocused ? "var(--shadow-bloom-soft)" : "none",
      transition: "all .25s var(--ease-out)", cursor: "pointer",
    }}>
      <div>
        <div style={{ fontSize: 16, fontWeight: 600 }}>{label}</div>
        {sub && <div style={{ fontSize: 13, color: "var(--ink-400)", marginTop: 4 }}>{sub}</div>}
      </div>
      {toggle ? (
        <div style={{
          width: 52, height: 30, borderRadius: 999, position: "relative",
          background: on ? "linear-gradient(135deg, #D4AF37, #8C6E1F)" : "rgba(255,255,255,.10)",
          boxShadow: on ? "0 0 14px rgba(212,175,55,.4)" : "none",
          transition: "all .3s var(--ease-out)",
        }}>
          <div style={{
            position: "absolute", top: 3, left: on ? 25 : 3, width: 24, height: 24, borderRadius: "50%",
            background: "#fff", boxShadow: "0 2px 6px rgba(0,0,0,.4)",
            transition: "left .3s var(--ease-out)",
          }} />
        </div>
      ) : value ? (
        <div style={{ display: "flex", alignItems: "center", gap: 10, color: "var(--gold-300)", fontWeight: 600, fontSize: 14 }}>
          {value}<Icon name="forward" size={14} color="currentColor" />
        </div>
      ) : null}
    </div>
  );
}

/* ============= PLAYER ============= */
function PlayerScreen() {
  return (
    <div style={{ position: "relative", width: "100%", height: "100%", background: "#000", overflow: "hidden" }}>
      <div style={{ position: "absolute", inset: 0,
        background: `center/cover no-repeat url("${DATA.B('player-frame', 2400, 1350)}")`,
        filter: "brightness(0.85)",
      }} />
      <div style={{ position: "absolute", inset: 0, background: "linear-gradient(to top, rgba(0,0,0,.85) 0%, transparent 35%, transparent 70%, rgba(0,0,0,.7) 100%)" }} />
      {/* Top bar */}
      <div style={{ position: "absolute", top: 0, left: 0, right: 0, padding: "32px 48px",
        display: "flex", justifyContent: "space-between", alignItems: "center", zIndex: 2,
      }}>
        <button className="btn btn-secondary" style={{ width: 50, height: 50, padding: 0, borderRadius: "50%", background: "rgba(0,0,0,.5)" }}><Icon name="back" size={20} /></button>
        <div style={{ display: "flex", flexDirection: "column", alignItems: "center" }}>
          <div className="t-eyebrow">Now playing</div>
          <div style={{ fontSize: 18, fontWeight: 700, marginTop: 4 }}>Crimson Horizon · 2024</div>
        </div>
        <div style={{ display: "flex", gap: 8 }}>
          <span className="pill pill-4k">4K</span>
          <span className="pill">Atmos</span>
        </div>
      </div>

      {/* Center play */}
      <div style={{ position: "absolute", inset: 0, display: "grid", placeItems: "center", zIndex: 2 }}>
        <button className="focusable is-focused" style={{
          width: 110, height: 110, borderRadius: "50%", border: "none", cursor: "pointer",
          background: "linear-gradient(135deg, #F0CB57, #D4AF37 60%, #B8932A)",
          display: "grid", placeItems: "center",
          boxShadow: "0 0 50px rgba(212,175,55,.55), 0 24px 60px rgba(0,0,0,.6)",
        }}>
          <Icon name="pause" size={42} color="#1A1407" />
        </button>
      </div>

      {/* Bottom controls */}
      <div style={{ position: "absolute", left: 48, right: 48, bottom: 36, zIndex: 2 }}>
        <div className="glass-strong" style={{ padding: 24, borderRadius: 22, boxShadow: "var(--shadow-float)" }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", marginBottom: 14 }}>
            <div>
              <div style={{ fontSize: 22, fontWeight: 700 }}>Crimson Horizon</div>
              <div style={{ fontSize: 13, color: "var(--ink-300)", marginTop: 4 }}>Chapter 8 · The Cartographer</div>
            </div>
            <div style={{ fontFamily: "var(--font-mono)", fontSize: 16, color: "var(--ink-200)" }}>
              <span style={{ color: "var(--gold-300)", fontWeight: 700 }}>01:24:18</span>
              <span style={{ opacity: .4, margin: "0 6px" }}>/</span>
              <span>02:14:32</span>
            </div>
          </div>
          {/* Progress */}
          <div style={{ position: "relative", height: 6, borderRadius: 3, background: "rgba(255,255,255,.10)", overflow: "visible" }}>
            <div style={{ position: "absolute", left: 0, top: 0, bottom: 0, width: "62%", borderRadius: 3,
              background: "linear-gradient(90deg, #F0CB57, #D4AF37)",
              boxShadow: "0 0 12px rgba(212,175,55,.6)" }} />
            <div style={{ position: "absolute", left: "62%", top: -5, width: 16, height: 16, borderRadius: "50%",
              background: "#fff", transform: "translateX(-50%)",
              boxShadow: "0 0 0 4px rgba(212,175,55,.4), 0 4px 12px rgba(0,0,0,.6)",
            }} />
          </div>
          {/* Buttons */}
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginTop: 22 }}>
            <div style={{ display: "flex", gap: 12 }}>
              {["back","play","forward"].map(n => (
                <button key={n} className="btn btn-secondary" style={{ width: 50, height: 50, padding: 0, borderRadius: "50%", background: "rgba(255,255,255,.06)" }}>
                  <Icon name={n} size={n === "play" ? 20 : 16} />
                </button>
              ))}
              <button className="btn btn-secondary" style={{ height: 50, borderRadius: 999 }}>
                <Icon name="forward" size={14} /><span>+10s</span>
              </button>
            </div>
            <div style={{ display: "flex", gap: 12 }}>
              {[["volume","Audio"],["info","Subtitles"],["hd","Quality"],["cast","Cast"]].map(([icon, l]) => (
                <button key={l} className="btn btn-secondary" style={{ height: 50, borderRadius: 999, background: "rgba(255,255,255,.06)" }}>
                  <Icon name={icon} size={14} /><span>{l}</span>
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

window.SCREENS = Object.assign(window.SCREENS || {}, { SearchScreen, SettingsScreen, PlayerScreen });

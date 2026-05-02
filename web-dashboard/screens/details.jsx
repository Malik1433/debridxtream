/* global React, DATA */

function MovieDetailScreen() {
  const H = DATA.HERO;
  return (
    <div style={{ display: "flex", height: "100%", background: "var(--bg-deepest)" }}>
      <Sidebar active="movies" collapsed />
      <main style={{ flex: 1, position: "relative", overflow: "hidden" }}>
        <div style={{ position: "absolute", inset: 0, background: `center/cover no-repeat url("${H.backdrop}")` }} />
        <div style={{ position: "absolute", inset: 0,
          background: "linear-gradient(to right, rgba(5,6,8,.96) 30%, rgba(5,6,8,.4) 70%, rgba(5,6,8,.0) 100%)" }} />
        <div style={{ position: "absolute", inset: 0,
          background: "linear-gradient(180deg, transparent 40%, rgba(5,6,8,.95) 95%)" }} />
        <div style={{ position: "relative", zIndex: 2, height: "100%", overflowY: "auto" }}>
          <TopBar title="" subtitle="" breadcrumb={["Movies","Thriller","Crimson Horizon"]} showSearch={false} />
          <div style={{ padding: "0 64px", display: "grid", gridTemplateColumns: "300px 1fr", gap: 48, marginTop: 8 }}>
            <div>
              <div style={{
                width: 300, height: 450, borderRadius: 18, overflow: "hidden",
                background: `center/cover no-repeat url("${DATA.P('hero-poster',600,900)}")`,
                boxShadow: "var(--shadow-float)",
                border: "1px solid rgba(212,175,55,.25)",
              }} />
              <div className="glass" style={{ marginTop: 18, padding: 16, borderRadius: 14, display: "flex", flexDirection: "column", gap: 10 }}>
                {[["Director","Mira Vance"],["Writers","Jonas Eel, Mira Vance"],["Studio","Ardent Pictures"],["Audio","Atmos · DTS-HD"],["Subtitles","42 languages"]].map(([k, v]) => (
                  <div key={k} style={{ display: "flex", justifyContent: "space-between", fontSize: 13 }}>
                    <span style={{ color: "var(--ink-400)" }}>{k}</span>
                    <span style={{ color: "var(--ink-100)", fontWeight: 600 }}>{v}</span>
                  </div>
                ))}
              </div>
            </div>
            <div>
              <div style={{ display: "flex", gap: 8, marginBottom: 14 }}>
                <span className="pill pill-gold">★ {H.rating} IMDb</span>
                <span className="pill" style={{ background: "rgba(45,212,119,.16)", borderColor: "rgba(45,212,119,.4)", color: "#7CE9AC" }}>{H.match}% Match</span>
                <span className="pill pill-4k">4K · DOLBY ATMOS</span>
                <span className="pill">PG-13</span>
              </div>
              <h1 style={{ margin: 0, fontSize: 64, fontWeight: 800, letterSpacing: "-0.03em", lineHeight: 1 }}>{H.title}</h1>
              <div style={{ marginTop: 10, display: "flex", gap: 14, color: "var(--ink-300)", fontSize: 14 }}>
                {H.meta.map((m,i) => <React.Fragment key={i}><span>{m}</span>{i < H.meta.length-1 && <span style={{opacity:.4}}>·</span>}</React.Fragment>)}
              </div>
              <div style={{ display: "flex", gap: 8, marginTop: 14 }}>
                {H.genres.map(g => <span key={g} className="pill">{g}</span>)}
              </div>
              <p style={{ marginTop: 22, fontSize: 17, color: "var(--ink-200)", lineHeight: 1.6, maxWidth: 760 }}>{H.synopsis}</p>
              <div style={{ display: "flex", gap: 12, marginTop: 26 }}>
                <button className="btn btn-primary focusable is-focused"><Icon name="play" size={16} color="#1A1407" /><span>Play 4K</span></button>
                <button className="btn btn-secondary"><Icon name="play" size={14} /><span>Trailer</span></button>
                <button className="btn btn-secondary" style={{ width: 50, padding: 0, borderRadius: "50%" }}><Icon name="plus" size={18} /></button>
                <button className="btn btn-secondary" style={{ width: 50, padding: 0, borderRadius: "50%" }}><Icon name="fav" size={16} /></button>
                <button className="btn btn-secondary" style={{ width: 50, padding: 0, borderRadius: "50%" }}><Icon name="download" size={16} /></button>
              </div>

              {/* Sources panel — debrid */}
              <div style={{ marginTop: 36 }}>
                <div className="section-header"><h3 className="section-title" style={{ fontSize: 22 }}>Available sources</h3><span className="pill pill-gold">12 cached on Real-Debrid</span></div>
                <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
                  {[
                    { q: "2160p", lbl: "4K HDR · Atmos", size: "42.1 GB", host: "RD · Cached", best: true },
                    { q: "2160p", lbl: "4K · DV", size: "31.4 GB", host: "AD · Cached" },
                    { q: "1080p", lbl: "BluRay · DTS-HD", size: "14.8 GB", host: "RD · Cached" },
                    { q: "1080p", lbl: "WEB-DL", size: "8.2 GB", host: "Premiumize" },
                  ].map((s, i) => <SourceRow key={i} s={s} focused={s.best} />)}
                </div>
              </div>

              {/* Cast */}
              <div style={{ marginTop: 36 }}>
                <div className="section-header"><h3 className="section-title" style={{ fontSize: 22 }}>Cast</h3></div>
                <div style={{ display: "flex", gap: 18 }}>
                  {["Saoirse Ronan","David Oyelowo","Mads Mikkelsen","Tessa Thompson","Bill Nighy"].map((n, i) => (
                    <div key={n} style={{ textAlign: "center", width: 90 }}>
                      <div style={{
                        width: 80, height: 80, borderRadius: "50%", margin: "0 auto",
                        background: `center/cover no-repeat url("${DATA.P('cast-' + i, 200, 200)}")`,
                        border: "1px solid rgba(255,255,255,.1)",
                      }} />
                      <div style={{ fontSize: 12, fontWeight: 600, marginTop: 8, lineHeight: 1.2 }}>{n}</div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}

function SourceRow({ s, focused }) {
  const { isFocused, bind } = useFocusable("src-" + s.q + s.size, true);
  const hi = focused || isFocused;
  return (
    <div {...bind} style={{
      display: "flex", alignItems: "center", gap: 14, padding: 14, borderRadius: 14, cursor: "pointer",
      background: hi ? "linear-gradient(135deg, rgba(212,175,55,.16), rgba(11,14,21,.6))" : "rgba(255,255,255,.04)",
      border: "1px solid " + (hi ? "rgba(212,175,55,.5)" : "rgba(255,255,255,.08)"),
      boxShadow: hi ? "var(--shadow-bloom-soft)" : "none",
      transition: "all .25s var(--ease-out)",
    }}>
      <div style={{
        padding: "6px 10px", borderRadius: 8,
        background: s.q === "2160p" ? "linear-gradient(135deg, #D4AF37, #8C6E1F)" : "rgba(255,255,255,.06)",
        color: s.q === "2160p" ? "#1A1407" : "var(--ink-100)", fontWeight: 800, fontSize: 12,
      }}>{s.q}</div>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: 14, fontWeight: 600 }}>{s.lbl}</div>
        <div style={{ fontSize: 12, color: "var(--ink-400)", marginTop: 2 }}>{s.host} · {s.size}</div>
      </div>
      <Icon name="play" size={14} color={hi ? "var(--gold-300)" : "var(--ink-400)"} />
    </div>
  );
}

/* ============= SERIES DETAIL ============= */
function SeriesDetailScreen() {
  const [season, setSeason] = React.useState(2);
  return (
    <div style={{ display: "flex", height: "100%", background: "var(--bg-deepest)" }}>
      <Sidebar active="series" collapsed />
      <main style={{ flex: 1, position: "relative", overflow: "hidden" }}>
        <div style={{ position: "absolute", inset: 0, background: `center/cover no-repeat url("${DATA.B('foundation-bg', 2400, 1200)}")` }} />
        <div style={{ position: "absolute", inset: 0, background: "linear-gradient(180deg, rgba(5,6,8,.65) 0%, rgba(5,6,8,.92) 60%, var(--bg-deepest) 100%)" }} />
        <div style={{ position: "relative", zIndex: 2, height: "100%", overflowY: "auto" }}>
          <TopBar title="" subtitle="" breadcrumb={["Series","Sci-Fi","Foundation"]} showSearch={false} />
          <div style={{ padding: "0 64px", maxWidth: 1100 }}>
            <div className="t-eyebrow">A Derbix exclusive</div>
            <h1 style={{ margin: "10px 0 8px", fontSize: 72, fontWeight: 800, letterSpacing: "-0.03em", lineHeight: 1 }}>Foundation</h1>
            <div style={{ display: "flex", gap: 8 }}>
              <span className="pill pill-gold">★ 8.4</span>
              <span className="pill">2024 · S2</span>
              <span className="pill pill-4k">4K HDR</span>
              <span className="pill">Sci‑Fi · Drama</span>
            </div>
            <p style={{ marginTop: 20, fontSize: 17, color: "var(--ink-200)", lineHeight: 1.55, maxWidth: 800 }}>
              A band of exiles must turn fate to their advantage as they race to save humanity and rebuild civilization amid the fall of the Galactic Empire.
            </p>
            <div style={{ display: "flex", gap: 12, marginTop: 22 }}>
              <button className="btn btn-primary"><Icon name="play" size={14} color="#1A1407" /><span>Resume S2 · E6</span></button>
              <button className="btn btn-secondary"><Icon name="info" size={14} /><span>Trailer</span></button>
              <button className="btn btn-secondary" style={{ width: 48, padding: 0, borderRadius: "50%" }}><Icon name="plus" size={16} /></button>
            </div>
          </div>

          <div style={{ marginTop: 44, padding: "0 64px 56px" }}>
            <div className="section-header"><h3 className="section-title">Episodes</h3>
              <div style={{ display: "flex", gap: 8 }}>
                {[1,2,3].map(s => (
                  <button key={s} onClick={() => setSeason(s)} className="focusable" style={{
                    padding: "8px 16px", borderRadius: 999, cursor: "pointer", border: "1px solid",
                    background: s === season ? "linear-gradient(135deg, #D4AF37, #B8932A)" : "rgba(255,255,255,.04)",
                    color: s === season ? "#1A1407" : "var(--ink-200)",
                    borderColor: s === season ? "transparent" : "rgba(255,255,255,.10)",
                    fontSize: 13, fontWeight: 700,
                  }}>Season {s}</button>
                ))}
              </div>
            </div>
            <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
              {DATA.EPISODES.map((e, i) => <EpisodeRow key={e.num} ep={e} active={i === 0} />)}
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}

function EpisodeRow({ ep, active }) {
  const { isFocused, bind } = useFocusable("ep-" + ep.num, true);
  const hi = active || isFocused;
  return (
    <div {...bind} style={{
      display: "grid", gridTemplateColumns: "260px 1fr auto", gap: 22, alignItems: "center",
      padding: 14, borderRadius: 18, cursor: "pointer",
      background: hi ? "linear-gradient(90deg, rgba(212,175,55,.12), rgba(11,14,21,.5))" : "rgba(255,255,255,.03)",
      border: "1px solid " + (hi ? "rgba(212,175,55,.4)" : "rgba(255,255,255,.06)"),
      boxShadow: hi ? "var(--shadow-bloom-soft)" : "0 4px 14px rgba(0,0,0,.3)",
      transition: "all .3s var(--ease-out)",
    }}>
      <div style={{ position: "relative", width: 260, height: 146, borderRadius: 12, overflow: "hidden", background: `center/cover no-repeat url("${ep.src}")` }}>
        <div style={{ position: "absolute", inset: 0, background: "linear-gradient(to top, rgba(0,0,0,.6), transparent 50%)" }} />
        <div style={{ position: "absolute", left: 10, top: 10, padding: "3px 8px", background: "rgba(0,0,0,.7)", color: "#fff", fontSize: 11, fontWeight: 700, borderRadius: 6 }}>{ep.duration}</div>
        {hi && <div style={{ position: "absolute", inset: 0, display: "grid", placeItems: "center" }}>
          <div style={{ width: 48, height: 48, borderRadius: "50%", background: "rgba(212,175,55,.95)", display: "grid", placeItems: "center", boxShadow: "0 0 20px rgba(212,175,55,.6)" }}>
            <Icon name="play" size={18} color="#1A1407" />
          </div>
        </div>}
      </div>
      <div>
        <div style={{ fontSize: 13, color: "var(--gold-300)", fontWeight: 700, letterSpacing: ".06em" }}>EPISODE {ep.num}</div>
        <div style={{ fontSize: 22, fontWeight: 700, marginTop: 4 }}>{ep.title}</div>
        <div style={{ fontSize: 14, color: "var(--ink-300)", marginTop: 6, lineHeight: 1.4, maxWidth: 580 }}>{ep.synopsis}</div>
      </div>
      <div style={{ width: 50, height: 50, borderRadius: "50%", background: "rgba(255,255,255,.04)", border: "1px solid rgba(255,255,255,.10)", display: "grid", placeItems: "center" }}>
        <Icon name="check" size={18} color={ep.num <= 1 ? "var(--success)" : "var(--ink-500)"} />
      </div>
    </div>
  );
}

window.SCREENS = Object.assign(window.SCREENS || {}, { MovieDetailScreen, SeriesDetailScreen });

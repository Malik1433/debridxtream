/* global React, DATA */

function LiveTVScreen() {
  const [selected, setSelected] = React.useState(0);
  const ch = DATA.CHANNELS[selected];
  return (
    <div style={{ display: "flex", height: "100%", background: "var(--bg-deepest)", position: "relative" }}>
      <Sidebar active="live" />
      <main style={{ flex: 1, display: "grid", gridTemplateColumns: "320px 1fr", overflow: "hidden" }}>
        {/* Channel list */}
        <div className="glass" style={{ borderRight: "1px solid rgba(255,255,255,.06)", display: "flex", flexDirection: "column", padding: "24px 0", background: "rgba(11,14,21,.6)" }}>
          <div style={{ padding: "0 22px 16px", display: "flex", alignItems: "center", justifyContent: "space-between" }}>
            <div>
              <div className="t-eyebrow">Channels</div>
              <div style={{ fontSize: 22, fontWeight: 700, marginTop: 4 }}>All Channels</div>
            </div>
            <span className="pill">{DATA.CHANNELS.length}</span>
          </div>
          <div style={{ padding: "8px 14px", display: "flex", gap: 6, flexWrap: "wrap" }}>
            {["All","Favs","Sports","News","Movies","Kids"].map((t, i) => (
              <span key={t} className="pill" style={i === 0 ? { borderColor: "var(--gold-500)", color: "var(--gold-300)", background: "rgba(212,175,55,.10)" } : {}}>{t}</span>
            ))}
          </div>
          <div style={{ flex: 1, overflowY: "auto", padding: "12px 12px" }}>
            {DATA.CHANNELS.map((c, i) => <ChannelRow key={c.id} ch={c} active={i === selected} onSelect={() => setSelected(i)} />)}
          </div>
        </div>

        {/* Now playing + EPG */}
        <div style={{ display: "flex", flexDirection: "column", overflow: "hidden" }}>
          <TopBar title="Live TV" subtitle={`${ch.name} · Channel ${ch.num}`} />
          <div style={{ padding: "0 48px 28px", flex: 1, overflowY: "auto" }}>
            {/* Now-playing card */}
            <div className="glass-strong" style={{ borderRadius: 24, overflow: "hidden", boxShadow: "var(--shadow-float)" }}>
              <div style={{
                aspectRatio: "16/9", background: `center/cover no-repeat url("${DATA.B('live-' + ch.id, 1600, 900)}")`,
                position: "relative",
              }}>
                <div style={{ position: "absolute", inset: 0, background: "linear-gradient(to top, rgba(5,6,8,.92), transparent 50%)" }} />
                <div style={{ position: "absolute", top: 22, left: 22, display: "flex", gap: 10 }}>
                  <span className="pill pill-live">Live</span>
                  <span className="pill">HD · 1080p</span>
                </div>
                <div style={{ position: "absolute", left: 28, right: 28, bottom: 26 }}>
                  <div style={{ fontSize: 14, color: "var(--gold-300)", fontWeight: 600, letterSpacing: ".06em" }}>{ch.name} · NOW</div>
                  <div style={{ fontSize: 36, fontWeight: 800, marginTop: 6, letterSpacing: "-0.01em" }}>{ch.now}</div>
                  <div style={{ display: "flex", alignItems: "center", gap: 14, marginTop: 14 }}>
                    <div style={{ flex: 1, height: 6, background: "rgba(255,255,255,.15)", borderRadius: 3, overflow: "hidden" }}>
                      <div style={{ width: `${ch.progress}%`, height: "100%", background: "linear-gradient(90deg, #F0CB57, #D4AF37)", boxShadow: "0 0 8px rgba(212,175,55,.6)" }} />
                    </div>
                    <span style={{ fontSize: 13, color: "var(--ink-300)", fontFamily: "var(--font-mono)" }}>20:00 — 21:00</span>
                  </div>
                </div>
              </div>
              <div style={{ display: "flex", gap: 10, padding: 22 }}>
                <button className="btn btn-primary focusable is-focused"><Icon name="play" size={14} color="#1A1407" /><span>Watch Live</span></button>
                <button className="btn btn-secondary"><Icon name="info" size={14} /><span>Programme Info</span></button>
                <button className="btn btn-secondary" style={{ width: 48, padding: 0, borderRadius: "50%" }}><Icon name="fav" size={16} /></button>
                <button className="btn btn-secondary" style={{ width: 48, padding: 0, borderRadius: "50%" }}><Icon name="cast" size={16} /></button>
              </div>
            </div>
            {/* EPG */}
            <div style={{ marginTop: 32 }}>
              <div className="section-header"><h3 className="section-title">Tonight on {ch.name}</h3></div>
              <div style={{ display: "flex", gap: 12, overflowX: "hidden" }}>
                {DATA.EPG_PROGRAMS.map((p, i) => <EpgCard key={i} p={p} live={p.live} />)}
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}

function ChannelRow({ ch, active, onSelect }) {
  const { isFocused, bind } = useFocusable("ch-" + ch.id);
  const highlight = active || isFocused;
  return (
    <div {...bind} onClick={() => { onSelect(); }} style={{
      display: "flex", alignItems: "center", gap: 14, padding: "10px 12px", borderRadius: 12, cursor: "pointer",
      background: highlight ? "linear-gradient(90deg, rgba(212,175,55,.18), rgba(212,175,55,.04) 80%)" : "transparent",
      border: "1px solid " + (highlight ? "rgba(212,175,55,.4)" : "transparent"),
      marginBottom: 4,
      transition: "all .2s var(--ease-out)",
    }}>
      <div style={{
        width: 44, height: 44, borderRadius: 10, flexShrink: 0,
        background: ch.logoColor, display: "grid", placeItems: "center",
        fontSize: 11, fontWeight: 800, color: "#fff", letterSpacing: ".05em",
        boxShadow: "0 4px 12px rgba(0,0,0,.4)",
      }}>{ch.name.split(" ").map(w => w[0]).slice(0,2).join("")}</div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", gap: 8 }}>
          <div style={{ fontSize: 14, fontWeight: 700, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{ch.name}</div>
          <div style={{ fontSize: 11, color: "var(--ink-400)", fontFamily: "var(--font-mono)" }}>{ch.num}</div>
        </div>
        <div style={{ fontSize: 12, color: "var(--ink-300)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", marginTop: 2 }}>{ch.now}</div>
        <div style={{ height: 2, background: "rgba(255,255,255,.10)", borderRadius: 1, marginTop: 6, overflow: "hidden" }}>
          <div style={{ width: `${ch.progress}%`, height: "100%", background: "var(--gold-500)" }} />
        </div>
      </div>
    </div>
  );
}

function EpgCard({ p, live }) {
  const { isFocused, bind } = useFocusable("epg-" + p.time);
  return (
    <div {...bind} style={{
      flex: "0 0 220px", padding: 16, borderRadius: 14, cursor: "pointer",
      background: live
        ? "linear-gradient(160deg, rgba(212,175,55,.18), rgba(11,14,21,.6))"
        : "rgba(255,255,255,.04)",
      border: "1px solid " + (live ? "rgba(212,175,55,.4)" : (isFocused ? "rgba(212,175,55,.4)" : "rgba(255,255,255,.06)")),
      boxShadow: isFocused ? "var(--shadow-bloom-soft)" : "0 4px 14px rgba(0,0,0,.3)",
      transform: isFocused ? "translateY(-3px)" : "none",
      transition: "all .25s var(--ease-out)",
    }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 8 }}>
        <span style={{ fontFamily: "var(--font-mono)", fontSize: 13, color: live ? "var(--gold-300)" : "var(--ink-400)", fontWeight: 700 }}>{p.time}</span>
        {live && <span className="pill pill-live" style={{ fontSize: 10, padding: "3px 8px" }}>Now</span>}
      </div>
      <div style={{ fontSize: 14, fontWeight: 600, lineHeight: 1.3 }}>{p.title}</div>
      {live && p.progress != null && (
        <div style={{ height: 3, background: "rgba(255,255,255,.15)", borderRadius: 2, marginTop: 12, overflow: "hidden" }}>
          <div style={{ width: `${p.progress}%`, height: "100%", background: "var(--gold-500)" }} />
        </div>
      )}
    </div>
  );
}

/* ============= MOVIES ============= */
function MoviesScreen() {
  const all = [...DATA.TRENDING, ...DATA.NEW_ARRIVALS, ...DATA.TOP10.map(t => ({ ...t, year: 2024, rating: 8.2 }))];
  const cats = ["All Movies","4K Premium","Action","Drama","Sci-Fi","Thriller","Comedy","Documentary","Animation"];
  return (
    <div style={{ display: "flex", height: "100%", background: "var(--bg-deepest)" }}>
      <Sidebar active="movies" />
      <main style={{ flex: 1, position: "relative", overflow: "hidden", display: "flex", flexDirection: "column" }}>
        <AmbientBloom />
        <TopBar title="Movies" subtitle="Curated cinema, debrid‑accelerated" right={
          <div style={{ display: "flex", gap: 8 }}>
            <span className="pill pill-gold">All quality 4K</span>
            <span className="pill">{all.length} titles</span>
          </div>
        } />
        <div style={{ padding: "0 48px 16px", display: "flex", gap: 8, flexWrap: "wrap", position: "relative", zIndex: 2 }}>
          {cats.map((c, i) => (
            <button key={c} className="focusable" style={{
              padding: "10px 18px", borderRadius: 999, cursor: "pointer",
              background: i === 0 ? "linear-gradient(135deg, #D4AF37, #B8932A)" : "rgba(255,255,255,.04)",
              color: i === 0 ? "#1A1407" : "var(--ink-200)",
              border: "1px solid " + (i === 0 ? "transparent" : "rgba(255,255,255,.10)"),
              fontSize: 13, fontWeight: 700, letterSpacing: ".02em",
              boxShadow: i === 0 ? "0 6px 18px rgba(212,175,55,.4)" : "none",
            }}>{c}</button>
          ))}
        </div>
        <div style={{ padding: "20px 48px 48px", flex: 1, overflowY: "auto", position: "relative", zIndex: 2 }}>
          <div className="section-header"><h3 className="section-title">Recently Added</h3></div>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(6, 1fr)", gap: 22 }}>
            {all.slice(0, 18).map((m, i) => (
              <Poster key={m.id + i} {...m} w={null} h={300} id={"mov-" + m.id + i} />
            ))}
          </div>
        </div>
      </main>
    </div>
  );
}

/* ============= SERIES ============= */
function SeriesScreen() {
  return (
    <div style={{ display: "flex", height: "100%", background: "var(--bg-deepest)" }}>
      <Sidebar active="series" />
      <main style={{ flex: 1, position: "relative", overflow: "hidden", display: "flex", flexDirection: "column" }}>
        <AmbientBloom />
        <TopBar title="Series" subtitle="Binge tonight" right={<span className="pill pill-gold">8 new this week</span>} />
        <div style={{ padding: "0 48px 48px", flex: 1, overflowY: "auto", position: "relative", zIndex: 2 }}>
          <Row title="Trending Series" action="See all">
            {DATA.SERIES.map(s => <Poster key={s.id} {...s} w={210} h={310} />)}
          </Row>
          <Row title="Critically Acclaimed" action="See all">
            {[...DATA.SERIES].reverse().map((s, i) => <Poster key={"crit-" + s.id} {...s} id={"crit-" + s.id} w={210} h={310} />)}
          </Row>
          <Row title="Continue Watching" action="See all">
            {DATA.CONTINUE.map(c => <ContinueCard2 key={c.id} item={c} />)}
          </Row>
        </div>
      </main>
    </div>
  );
}

function ContinueCard2({ item }) {
  const { isFocused, bind } = useFocusable("cw2-" + item.id);
  return (
    <div {...bind} style={{
      width: 320, height: 200, borderRadius: 18, overflow: "hidden", flexShrink: 0,
      position: "relative",
      border: "1px solid " + (isFocused ? "rgba(212,175,55,.55)" : "rgba(255,255,255,.06)"),
      boxShadow: isFocused
        ? "0 0 0 2px rgba(212,175,55,.6), 0 0 32px rgba(212,175,55,.35), 0 22px 50px rgba(0,0,0,.7)"
        : "0 8px 22px rgba(0,0,0,.45)",
      transform: isFocused ? "translateY(-6px) scale(1.04)" : "scale(1)",
      transition: "all .3s var(--ease-out)", cursor: "pointer", zIndex: isFocused ? 5 : 1,
      background: `center/cover no-repeat url("${item.src}")`,
    }}>
      <div style={{ position: "absolute", inset: 0, background: "linear-gradient(to top, rgba(5,6,8,.95), transparent 60%)" }} />
      <div style={{ position: "absolute", left: 14, right: 14, bottom: 14 }}>
        <div style={{ fontSize: 14, fontWeight: 700 }}>{item.title}</div>
        <div style={{ fontSize: 12, color: "var(--ink-300)", marginTop: 2 }}>{item.subtitle}</div>
        <div style={{ height: 4, background: "rgba(255,255,255,.15)", borderRadius: 2, overflow: "hidden", marginTop: 8 }}>
          <div style={{ width: `${item.progress}%`, height: "100%", background: "linear-gradient(90deg, #F0CB57, #D4AF37)" }} />
        </div>
      </div>
    </div>
  );
}

window.SCREENS = Object.assign(window.SCREENS || {}, { LiveTVScreen, MoviesScreen, SeriesScreen });

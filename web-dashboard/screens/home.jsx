/* global React, DATA */
const { useState: useStateH } = React;

function HomeScreen() {
  const H = DATA.HERO;
  return (
    <div style={{ display: "flex", height: "100%", background: "var(--bg-deepest)" }}>
      <Sidebar active="home" />
      <main style={{ flex: 1, position: "relative", overflow: "hidden" }}>
        {/* Hero backdrop */}
        <div style={{ position: "absolute", inset: 0, zIndex: 0,
          background: `center/cover no-repeat url("${H.backdrop}")`,
          maskImage: "linear-gradient(180deg, #000 0%, #000 55%, transparent 92%)",
          WebkitMaskImage: "linear-gradient(180deg, #000 0%, #000 55%, transparent 92%)",
        }} />
        <div style={{ position: "absolute", inset: 0, zIndex: 1,
          background: "linear-gradient(110deg, rgba(5,6,8,0.95) 0%, rgba(5,6,8,0.55) 45%, rgba(5,6,8,0.15) 75%, rgba(5,6,8,0.0) 100%)",
        }} />
        <div style={{ position: "absolute", inset: 0, zIndex: 1,
          background: "linear-gradient(180deg, transparent 50%, rgba(5,6,8,0.8) 80%, var(--bg-deepest) 100%)",
        }} />

        <div style={{ position: "relative", zIndex: 2, height: "100%", overflowY: "auto" }}>
          <TopBar title="" subtitle="" right={
            <button className="btn btn-secondary" style={{ width: 48, height: 48, padding: 0, borderRadius: "50%" }}>
              <Icon name="bell" size={20} />
            </button>
          } />
          {/* Hero content */}
          <div style={{ padding: "0 64px", maxWidth: 880, marginTop: 8 }}>
            <div style={{ display: "flex", gap: 8, marginBottom: 14 }}>
              <span className="pill pill-gold">★ {H.rating}</span>
              <span className="pill" style={{ background: "rgba(45,212,119,.16)", borderColor: "rgba(45,212,119,.4)", color: "#7CE9AC" }}>{H.match}% Match</span>
              <span className="pill pill-4k">4K · DOLBY ATMOS</span>
            </div>
            <h1 style={{ margin: 0, fontSize: 76, fontWeight: 800, letterSpacing: "-0.03em", lineHeight: 1, textShadow: "0 4px 24px rgba(0,0,0,.7)" }}>{H.title}</h1>
            <div style={{ marginTop: 12, fontSize: 18, color: "var(--gold-300)", letterSpacing: ".06em", fontWeight: 600 }}>{H.tagline}</div>
            <p style={{ marginTop: 18, fontSize: 17, color: "var(--ink-200)", lineHeight: 1.55, maxWidth: 720 }}>{H.synopsis}</p>
            <div style={{ display: "flex", gap: 14, marginTop: 14, color: "var(--ink-300)", fontSize: 14, fontWeight: 500 }}>
              {H.meta.map((m, i) => (
                <React.Fragment key={i}>
                  <span>{m}</span>
                  {i < H.meta.length - 1 && <span style={{ opacity: .4 }}>·</span>}
                </React.Fragment>
              ))}
            </div>
            <div style={{ display: "flex", gap: 12, marginTop: 28 }}>
              <button className="btn btn-primary focusable is-focused" style={{ padding: "16px 28px", fontSize: 16 }}>
                <Icon name="play" size={16} color="#1A1407" /><span>Play Now</span>
              </button>
              <button className="btn btn-secondary" style={{ padding: "16px 24px" }}>
                <Icon name="info" size={16} /><span>More Info</span>
              </button>
              <button className="btn btn-secondary" style={{ width: 52, height: 52, padding: 0, borderRadius: "50%" }}>
                <Icon name="plus" size={20} />
              </button>
              <button className="btn btn-secondary" style={{ width: 52, height: 52, padding: 0, borderRadius: "50%" }}>
                <Icon name="fav" size={18} />
              </button>
            </div>
          </div>

          {/* Rows */}
          <div style={{ marginTop: 70, padding: "0 0 0 64px" }}>
            <Row title="Continue Watching">
              {DATA.CONTINUE.map(c => <ContinueCard key={c.id} item={c} />)}
            </Row>
            <Row title="Trending Now">
              {DATA.TRENDING.map(m => <Poster key={m.id} {...m} w={180} h={270} />)}
            </Row>
            <Row title="New Arrivals">
              {DATA.NEW_ARRIVALS.map(m => <Poster key={m.id} {...m} w={180} h={270} />)}
            </Row>
            <Row title="Top 10 in your country">
              {DATA.TOP10.slice(0,7).map((m, i) => <RankedPoster key={m.id} item={m} rank={i+1} />)}
            </Row>
          </div>
        </div>
      </main>
    </div>
  );
}

function ContinueCard({ item }) {
  const { isFocused, bind } = useFocusable("cont-" + item.id);
  return (
    <div {...bind} style={{
      width: 320, height: 200, borderRadius: 18, overflow: "hidden", flexShrink: 0,
      position: "relative",
      border: "1px solid " + (isFocused ? "rgba(212,175,55,.55)" : "rgba(255,255,255,.06)"),
      boxShadow: isFocused
        ? "0 0 0 2px rgba(212,175,55,.6), 0 0 32px rgba(212,175,55,.35), 0 22px 50px rgba(0,0,0,.7)"
        : "0 8px 22px rgba(0,0,0,.45)",
      transform: isFocused ? "translateY(-6px) scale(1.05)" : "scale(1)",
      transition: "all .3s var(--ease-out)",
      cursor: "pointer", zIndex: isFocused ? 5 : 1,
      background: `center/cover no-repeat url("${item.src}")`,
    }}>
      <div style={{ position: "absolute", inset: 0, background: "linear-gradient(to top, rgba(5,6,8,.95), rgba(5,6,8,.2) 60%, transparent)" }} />
      <div style={{ position: "absolute", inset: 0, display: "grid", placeItems: "center" }}>
        <div style={{
          width: 56, height: 56, borderRadius: "50%",
          background: "rgba(212,175,55,.95)", display: "grid", placeItems: "center",
          boxShadow: "0 0 24px rgba(212,175,55,.5)",
        }}>
          <Icon name="play" size={22} color="#1A1407" />
        </div>
      </div>
      <div style={{ position: "absolute", left: 14, right: 14, bottom: 14 }}>
        <div style={{ fontSize: 15, fontWeight: 700 }}>{item.title}</div>
        <div style={{ fontSize: 12, color: "var(--ink-300)", marginTop: 2 }}>{item.subtitle}</div>
        <div style={{ height: 4, background: "rgba(255,255,255,.15)", borderRadius: 2, overflow: "hidden", marginTop: 10 }}>
          <div style={{ width: `${item.progress}%`, height: "100%", background: "linear-gradient(90deg, #F0CB57, #D4AF37)" }} />
        </div>
      </div>
    </div>
  );
}

function RankedPoster({ item, rank }) {
  const { isFocused, bind } = useFocusable("rank-" + item.id);
  return (
    <div {...bind} style={{
      display: "flex", alignItems: "flex-end", flexShrink: 0, cursor: "pointer",
      transform: isFocused ? "translateY(-6px) scale(1.04)" : "scale(1)",
      transition: "all .3s var(--ease-out)",
      zIndex: isFocused ? 5 : 1,
    }}>
      <div style={{
        fontSize: 220, fontWeight: 900, lineHeight: .8,
        WebkitTextStroke: "3px " + (isFocused ? "var(--gold-300)" : "rgba(255,255,255,.18)"),
        color: "transparent",
        marginRight: -32, fontFamily: "var(--font-display)",
        textShadow: isFocused ? "0 0 30px rgba(212,175,55,.4)" : "none",
        transition: "all .3s var(--ease-out)",
      }}>{rank}</div>
      <div style={{
        width: 160, height: 240, borderRadius: 14, overflow: "hidden",
        background: `center/cover no-repeat url("${item.src}")`,
        border: "1px solid " + (isFocused ? "rgba(212,175,55,.55)" : "rgba(255,255,255,.08)"),
        boxShadow: isFocused ? "0 0 32px rgba(212,175,55,.4), 0 22px 50px rgba(0,0,0,.7)" : "0 8px 22px rgba(0,0,0,.5)",
      }} />
    </div>
  );
}

window.SCREENS = Object.assign(window.SCREENS || {}, { HomeScreen });

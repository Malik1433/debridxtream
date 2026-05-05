/* global React */
const { useState, useEffect, useRef, useCallback, createContext, useContext } = React;

/* =====================================================
   Logo / Wordmark
   ===================================================== */
function LogoMark({ size = 56, glow = true }) {
  // Reinterpreted "TV with antenna" mark, gold on dark.
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" fill="none" style={{ filter: glow ? "drop-shadow(0 0 12px rgba(212,175,55,.45))" : "none" }}>
      <defs>
        <linearGradient id="logoGold" x1="0" y1="0" x2="64" y2="64">
          <stop offset="0%" stopColor="#F0CB57" />
          <stop offset="55%" stopColor="#D4AF37" />
          <stop offset="100%" stopColor="#8C6E1F" />
        </linearGradient>
      </defs>
      {/* antenna */}
      <path d="M22 12 L31 24 M42 12 L33 24" stroke="url(#logoGold)" strokeWidth="3" strokeLinecap="round" />
      <circle cx="22" cy="11" r="2.4" fill="url(#logoGold)" />
      <circle cx="42" cy="11" r="2.4" fill="url(#logoGold)" />
      {/* TV body */}
      <rect x="8" y="22" width="48" height="32" rx="6" stroke="url(#logoGold)" strokeWidth="3" fill="rgba(212,175,55,0.06)" />
      {/* play triangle inside screen */}
      <path d="M28 32 L28 44 L40 38 Z" fill="url(#logoGold)" />
      {/* feet */}
      <path d="M20 58 L26 54 M44 58 L38 54" stroke="url(#logoGold)" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

function Wordmark({ size = 22, sub = false }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
      <LogoMark size={size * 1.5} glow={false} />
      <div style={{ display: "flex", flexDirection: "column", lineHeight: 1 }}>
        <div style={{
          fontSize: size, fontWeight: 800, letterSpacing: "0.18em",
          background: "linear-gradient(180deg, #F7E7B5 0%, #D4AF37 60%, #8C6E1F 100%)",
          WebkitBackgroundClip: "text", WebkitTextFillColor: "transparent",
          backgroundClip: "text", color: "transparent",
        }}>
          DEBRIDXTREAM
        </div>
        {sub && <div style={{ fontSize: size * 0.42, marginTop: 4, color: "var(--ink-400)", letterSpacing: "0.32em", fontWeight: 600 }}>CINEMATIC · IPTV</div>}
      </div>
    </div>
  );
}

/* =====================================================
   Icon — minimal inline SVG set, gold-tintable
   ===================================================== */
const ICON_PATHS = {
  home:    "M3 11.5L12 4l9 7.5V20a1 1 0 0 1-1 1h-5v-7h-6v7H4a1 1 0 0 1-1-1z",
  live:    "M5 6h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zm3-3h8M9 21h6",
  movies:  "M4 5h16v14H4zM4 9h16M8 5v14M16 5v14",
  series:  "M3 7h18v12H3zM7 3l5 4 5-4",
  search:  "M11 19a8 8 0 1 1 0-16 8 8 0 0 1 0 16zm5-3l5 5",
  settings:"M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zm9 4-2 1 .4 2.2-2 1.6-2-1L14 17l-2-1-2 1-1.4-1.2-2-1.6L7 12 5 11l1-2.2 2 1L9 7l2-1 2 1 1-1.4 2 1L17 5l2-.6 2 1.6L20.6 8 22 9z",
  bell:    "M6 16V11a6 6 0 1 1 12 0v5l2 2H4l2-2zM10 20a2 2 0 0 0 4 0",
  play:    "M7 5l13 7-13 7z",
  pause:   "M6 5h4v14H6zM14 5h4v14h-4z",
  plus:    "M12 5v14M5 12h14",
  star:    "M12 3l2.6 6 6.4.6-4.9 4.4 1.5 6.5L12 17.3 6.4 20.5 7.9 14 3 9.6 9.4 9z",
  arrowR:  "M5 12h14M13 5l7 7-7 7",
  arrowL:  "M19 12H5M11 5l-7 7 7 7",
  check:   "M5 12l5 5 9-11",
  user:    "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm-7 9a7 7 0 0 1 14 0",
  signal:  "M3 18h2M7 14h2v4H7zM11 10h2v8h-2zM15 6h2v12h-2zM19 2h2v16h-2",
  download:"M12 3v12m0 0l-4-4m4 4l4-4M5 21h14",
  fav:     "M12 21s-7-4.5-9.5-9C.7 8.6 3 5 6.5 5 8.5 5 10 6 12 8c2-2 3.5-3 5.5-3C21 5 23.3 8.6 21.5 12 19 16.5 12 21 12 21z",
  menu:    "M4 6h16M4 12h16M4 18h16",
  close:   "M5 5l14 14M19 5L5 19",
  cast:    "M3 17v3h3a3 3 0 0 0-3-3zm0-4v2a5 5 0 0 1 5 5h2a7 7 0 0 0-7-7zm0-4v2a9 9 0 0 1 9 9h2a11 11 0 0 0-11-11zm18-5H3a2 2 0 0 0-2 2v3h2V6h18v12h-7v2h7a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2z",
  film:    "M4 5h16v14H4zM4 5v14M20 5v14M4 9h2M4 13h2M4 17h2M18 9h2M18 13h2M18 17h2",
  clock:   "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2",
  back:    "M15 18l-6-6 6-6",
  forward: "M9 18l6-6-6-6",
  volume:  "M3 10v4h4l5 4V6L7 10H3zM16 8a5 5 0 0 1 0 8M19 5a8 8 0 0 1 0 14",
  hd:      "M3 8h18v8H3z",
  globe:   "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18",
  shield:  "M12 3l8 3v6c0 5-4 8-8 9-4-1-8-4-8-9V6l8-3z",
  info:    "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 8h.01M11 12h1v5h1",
  qr:      "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h3v3h-3zM18 14h3M14 18h3v3h-3zM18 21h3",
  trending:"M3 17l6-6 4 4 8-8M14 7h7v7",
};
function Icon({ name, size = 18, color = "currentColor", strokeWidth = 1.8, fill = "none", style }) {
  const d = ICON_PATHS[name];
  if (!d) return null;
  // some icons are best filled
  const fillIcons = ["play","pause","star","fav","film","logoplay"];
  const isFill = fillIcons.includes(name);
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill={isFill ? color : "none"} stroke={isFill ? "none" : color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" style={style}>
      <path d={d} />
    </svg>
  );
}

/* =====================================================
   Focus ring helper — simulates D-pad TV focus
   Click anywhere clears focus; click an item to focus it.
   ===================================================== */
const FocusContext = createContext({ focused: null, setFocused: () => {} });
function FocusProvider({ children, initial = null }) {
  const [focused, setFocused] = useState(initial);
  return <FocusContext.Provider value={{ focused, setFocused }}>{children}</FocusContext.Provider>;
}
function useFocusable(id, soft = false) {
  const { focused, setFocused } = useContext(FocusContext);
  const isFocused = focused === id;
  return {
    isFocused,
    bind: {
      className: "focusable" + (isFocused ? (soft ? " is-focused-soft" : " is-focused") : ""),
      onClick: (e) => { e.stopPropagation(); setFocused(id); },
      onMouseEnter: () => setFocused(id),
      tabIndex: 0,
    },
  };
}

/* =====================================================
   Poster (glass card with poster inset)
   ===================================================== */
function Poster({ id, src, title, year, rating, badge, focusable = true, w = 200, h = 300, hideMeta = false }) {
  const { isFocused, bind } = focusable ? useFocusable(id || title) : { isFocused: false, bind: {} };
  return (
    <div
      {...bind}
      style={{
        width: w, height: h, borderRadius: 18, position: "relative",
        background: "linear-gradient(180deg, rgba(255,255,255,.05), rgba(255,255,255,.02))",
        padding: 6,
        border: "1px solid " + (isFocused ? "rgba(212,175,55,.55)" : "rgba(255,255,255,.06)"),
        boxShadow: isFocused
          ? "0 0 0 2px rgba(212,175,55,.6), 0 0 32px rgba(212,175,55,.35), 0 22px 50px rgba(0,0,0,.7)"
          : "0 1px 0 rgba(255,255,255,.04) inset, 0 8px 22px rgba(0,0,0,.45)",
        backdropFilter: "blur(10px)",
        cursor: "pointer",
        transform: isFocused ? "translateY(-6px) scale(1.05)" : "translateY(0) scale(1)",
        transition: "all .3s var(--ease-out)",
        zIndex: isFocused ? 5 : 1,
      }}
    >
      <div style={{
        width: "100%", height: "100%", borderRadius: 14, overflow: "hidden",
        background: src ? `center/cover no-repeat url("${src}")` : "linear-gradient(135deg, #1F2433, #0B0E15)",
        position: "relative",
      }}>
        {/* fallback film tile */}
        {!src && (
          <div style={{ position: "absolute", inset: 0, display: "grid", placeItems: "center", color: "rgba(255,255,255,.18)" }}>
            <Icon name="film" size={42} />
          </div>
        )}
        {/* badges */}
        {badge && (
          <div style={{ position: "absolute", top: 10, left: 10 }}>
            {badge === "4K" && <span className="pill pill-4k">4K</span>}
            {badge === "HDR" && <span className="pill pill-gold">HDR</span>}
            {badge === "NEW" && <span className="pill" style={{ background: "var(--info)", color: "#fff", border: "none", fontWeight: 800 }}>NEW</span>}
            {badge === "TOP10" && <span className="pill pill-gold">TOP 10</span>}
          </div>
        )}
        {/* meta scrim */}
        {!hideMeta && (
          <div style={{
            position: "absolute", left: 0, right: 0, bottom: 0,
            padding: "28px 14px 12px",
            background: "linear-gradient(to top, rgba(5,6,8,0.95) 0%, rgba(5,6,8,0.6) 60%, transparent 100%)",
          }}>
            <div style={{ fontSize: 14, fontWeight: 700, color: "var(--ink-100)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{title}</div>
            <div style={{ display: "flex", alignItems: "center", gap: 8, marginTop: 4, fontSize: 11, color: "var(--ink-300)" }}>
              {rating && (<><Icon name="star" size={11} color="#D4AF37" /><span style={{ color: "var(--gold-300)" }}>{rating}</span><span style={{ opacity: .4 }}>·</span></>)}
              {year && <span>{year}</span>}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/* =====================================================
   Sidebar (TV nav)
   ===================================================== */
function Sidebar({ active, onNavigate, collapsed = false }) {
  const items = [
    { id: "home", label: "Home", icon: "home" },
    { id: "live", label: "Live TV", icon: "live" },
    { id: "movies", label: "Movies", icon: "movies" },
    { id: "series", label: "Series", icon: "series" },
    { id: "search", label: "Search", icon: "search" },
    { id: "settings", label: "Settings", icon: "settings" },
  ];
  const w = collapsed ? 96 : 260;
  return (
    <aside style={{
      width: w, flexShrink: 0, height: "100%",
      background: "linear-gradient(180deg, rgba(11,14,21,0.92), rgba(5,6,8,0.96))",
      borderRight: "1px solid rgba(255,255,255,.06)",
      display: "flex", flexDirection: "column",
      padding: "32px 16px",
      backdropFilter: "blur(20px)",
      transition: "width .3s var(--ease-out)",
    }}>
      <div style={{ padding: "0 8px 28px", borderBottom: "1px solid rgba(255,255,255,.05)", marginBottom: 24 }}>
        {collapsed ? <LogoMark size={40} /> : <Wordmark size={16} />}
      </div>
      <nav style={{ display: "flex", flexDirection: "column", gap: 4, flex: 1 }}>
        {items.map(it => {
          const isActive = active === it.id;
          return (
            <button
              key={it.id}
              onClick={() => onNavigate?.(it.id)}
              className="focusable"
              style={{
                display: "flex", alignItems: "center", gap: 14,
                padding: collapsed ? "14px 0" : "14px 16px",
                justifyContent: collapsed ? "center" : "flex-start",
                borderRadius: 12,
                background: isActive
                  ? "linear-gradient(90deg, rgba(212,175,55,.18), rgba(212,175,55,.04) 70%)"
                  : "transparent",
                border: "1px solid " + (isActive ? "rgba(212,175,55,.35)" : "transparent"),
                color: isActive ? "#F4F1E6" : "var(--ink-300)",
                cursor: "pointer", textAlign: "left",
                position: "relative",
                fontSize: 15, fontWeight: isActive ? 700 : 500,
              }}
            >
              {isActive && (
                <span style={{
                  position: "absolute", left: -16, top: "20%", bottom: "20%",
                  width: 3, borderRadius: "0 3px 3px 0",
                  background: "linear-gradient(180deg, #F0CB57, #B8932A)",
                  boxShadow: "0 0 12px rgba(212,175,55,.7)",
                }} />
              )}
              <Icon name={it.icon} size={20} color={isActive ? "var(--gold-300)" : "currentColor"} />
              {!collapsed && <span>{it.label}</span>}
            </button>
          );
        })}
      </nav>
      <div style={{ paddingTop: 18, borderTop: "1px solid rgba(255,255,255,.05)" }}>
        <div className="glass" style={{
          padding: collapsed ? 8 : 14, borderRadius: 14, display: "flex", alignItems: "center",
          gap: 12, justifyContent: collapsed ? "center" : "flex-start",
        }}>
          <div style={{
            width: 36, height: 36, borderRadius: "50%",
            background: "linear-gradient(135deg, #D4AF37, #8C6E1F)",
            display: "grid", placeItems: "center",
            fontWeight: 800, color: "#1A1407", fontSize: 14, flexShrink: 0,
          }}>JS</div>
          {!collapsed && (
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 13, fontWeight: 700, color: "var(--ink-100)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>Jamie S.</div>
              <div style={{ fontSize: 11, color: "var(--gold-300)", display: "flex", alignItems: "center", gap: 4 }}>
                <span style={{ width: 6, height: 6, borderRadius: "50%", background: "#2DD477", boxShadow: "0 0 6px #2DD477" }} />
                Premium
              </div>
            </div>
          )}
        </div>
      </div>
    </aside>
  );
}

/* =====================================================
   TopBar — same pattern across pages
   ===================================================== */
function TopBar({ title, subtitle, breadcrumb, showSearch = true, right }) {
  const t = new Date();
  const time = t.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  return (
    <header style={{
      display: "flex", alignItems: "center", justifyContent: "space-between",
      padding: "28px 48px 20px", gap: 24,
    }}>
      <div style={{ minWidth: 0 }}>
        {breadcrumb && (
          <div style={{ display: "flex", alignItems: "center", gap: 8, color: "var(--ink-400)", fontSize: 13, marginBottom: 6, fontWeight: 600, letterSpacing: ".04em" }}>
            {breadcrumb.map((b, i) => (
              <React.Fragment key={i}>
                <span>{b}</span>
                {i < breadcrumb.length - 1 && <Icon name="forward" size={12} />}
              </React.Fragment>
            ))}
          </div>
        )}
        <h1 style={{ margin: 0, fontSize: 36, fontWeight: 800, letterSpacing: "-0.02em", color: "var(--ink-100)" }}>{title}</h1>
        {subtitle && <div style={{ marginTop: 6, fontSize: 15, color: "var(--ink-300)" }}>{subtitle}</div>}
      </div>
      <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
        {right}
        {showSearch && (
          <button className="btn btn-secondary" style={{ width: 48, height: 48, padding: 0, borderRadius: "50%" }}>
            <Icon name="search" size={20} />
          </button>
        )}
        <div className="glass" style={{ display: "flex", alignItems: "center", gap: 10, padding: "10px 16px", borderRadius: 999 }}>
          <Icon name="signal" size={14} color="#2DD477" />
          <span style={{ fontSize: 13, fontWeight: 600, color: "var(--ink-200)" }}>{time}</span>
        </div>
      </div>
    </header>
  );
}

/* =====================================================
   Ambient bloom (cinematic background blobs)
   ===================================================== */
function AmbientBloom() {
  return (
    <>
      <div style={{
        position: "absolute", top: -200, right: -200, width: 600, height: 600,
        borderRadius: "50%",
        background: "radial-gradient(circle, rgba(212,175,55,.18), transparent 70%)",
        filter: "blur(60px)",
        animation: "ambientPulse 6s var(--ease-out) infinite",
        pointerEvents: "none",
      }} />
      <div style={{
        position: "absolute", bottom: -250, left: -150, width: 700, height: 700,
        borderRadius: "50%",
        background: "radial-gradient(circle, rgba(91,168,255,.10), transparent 70%)",
        filter: "blur(80px)",
        animation: "ambientPulse 8s var(--ease-out) infinite reverse",
        pointerEvents: "none",
      }} />
    </>
  );
}

/* =====================================================
   HorizontalRow — used across Home / Live / Movies
   ===================================================== */
function Row({ title, action = "See all", children }) {
  return (
    <section style={{ marginBottom: 44 }}>
      <div className="section-header" style={{ paddingRight: 48 }}>
        <h3 className="section-title">{title}</h3>
        {action && <a className="section-link">{action} ›</a>}
      </div>
      <div style={{ display: "flex", gap: 18, overflowX: "hidden", paddingBottom: 12, paddingLeft: 4, paddingRight: 48 }}>
        {children}
      </div>
    </section>
  );
}

/* expose globally */
Object.assign(window, { LogoMark, Wordmark, Icon, FocusProvider, useFocusable, Poster, Sidebar, TopBar, AmbientBloom, Row });

/* global React, DATA */
const { useState, useEffect } = React;

/* =====================================================
   SPLASH
   ===================================================== */
function SplashScreen() {
  return (
    <div className="app-bg" style={{ width: "100%", height: "100%", display: "grid", placeItems: "center", position: "relative" }}>
      <AmbientBloom />
      <div style={{ textAlign: "center", zIndex: 2 }}>
        <div style={{ display: "grid", placeItems: "center", marginBottom: 36, animation: "fadeUp .8s var(--ease-out) both" }}>
          <div style={{
            width: 200, height: 200, borderRadius: 48,
            background: "radial-gradient(circle at 30% 30%, rgba(212,175,55,.30), rgba(11,14,21,.0) 70%), linear-gradient(160deg, #11151F, #050608)",
            border: "1px solid rgba(212,175,55,.35)",
            display: "grid", placeItems: "center",
            boxShadow: "0 0 60px rgba(212,175,55,.35), 0 30px 80px rgba(0,0,0,.6), 0 1px 0 rgba(255,255,255,.06) inset",
          }}>
            <LogoMark size={120} />
          </div>
        </div>
        <div style={{ animation: "fadeUp .9s .2s var(--ease-out) both" }}>
          <Wordmark size={28} sub />
        </div>
        <div style={{ marginTop: 60, display: "flex", alignItems: "center", justifyContent: "center", gap: 12, animation: "fadeUp 1s .4s var(--ease-out) both" }}>
          {[0,1,2].map(i => (
            <span key={i} style={{
              width: 8, height: 8, borderRadius: 999,
              background: "var(--gold-500)",
              animation: `pulseDot 1.2s ${i * 0.15}s infinite`,
            }} />
          ))}
        </div>
        <div style={{ marginTop: 20, color: "var(--ink-400)", fontSize: 13, letterSpacing: ".3em", textTransform: "uppercase", fontWeight: 600 }}>
          Preparing your library…
        </div>
      </div>
    </div>
  );
}

/* =====================================================
   LOGIN — refined
   ===================================================== */
function LoginScreen() {
  const [tab, setTab] = useState("xtream");
  return (
    <div className="app-bg" style={{ width: "100%", height: "100%", position: "relative", display: "grid", gridTemplateColumns: "1.05fr .95fr" }}>
      <AmbientBloom />
      {/* left pane — cinematic backdrop */}
      <div style={{
        position: "relative",
        background: `linear-gradient(110deg, rgba(5,6,8,0.65) 0%, rgba(5,6,8,0.3) 50%, rgba(5,6,8,0.85) 100%), center/cover no-repeat url("${DATA.B('login-cinema',1600,1080)}")`,
        padding: 72, display: "flex", flexDirection: "column", justifyContent: "space-between",
      }}>
        <Wordmark size={20} />
        <div style={{ maxWidth: 540 }}>
          <div className="t-eyebrow" style={{ marginBottom: 14 }}>· The cinematic experience ·</div>
          <h1 className="t-display" style={{ marginTop: 0, marginBottom: 16 }}>
            Your library, <span style={{
              background: "linear-gradient(180deg, #F7E7B5, #D4AF37 60%, #8C6E1F)",
              WebkitBackgroundClip: "text", color: "transparent",
            }}>cinematic.</span>
          </h1>
          <p style={{ fontSize: 18, color: "var(--ink-300)", lineHeight: 1.55, margin: 0 }}>
            Live channels, films and series — unified, debrid‑accelerated, and beautifully presented across every screen you own.
          </p>
          <div style={{ display: "flex", gap: 24, marginTop: 32 }}>
            {[["12K+","Channels"],["80K+","Movies"],["6K+","Series"]].map(([n, l]) => (
              <div key={l}>
                <div style={{ fontSize: 28, fontWeight: 800, color: "var(--gold-300)", letterSpacing: "-0.02em" }}>{n}</div>
                <div style={{ fontSize: 12, color: "var(--ink-400)", textTransform: "uppercase", letterSpacing: ".2em", fontWeight: 600 }}>{l}</div>
              </div>
            ))}
          </div>
        </div>
        <div style={{ color: "var(--ink-500)", fontSize: 12, letterSpacing: ".2em", textTransform: "uppercase" }}>v 4.2.0 · Premium tier</div>
      </div>

      {/* right pane — form card */}
      <div style={{ display: "grid", placeItems: "center", padding: 48, position: "relative", zIndex: 2 }}>
        <div className="glass-strong fade-up" style={{
          width: 480, padding: "40px 40px 32px", borderRadius: 24,
          boxShadow: "var(--shadow-float)",
        }}>
          <h2 style={{ margin: 0, fontSize: 28, fontWeight: 800, letterSpacing: "-0.01em" }}>Sign in</h2>
          <p style={{ marginTop: 6, marginBottom: 24, color: "var(--ink-300)", fontSize: 14 }}>Connect your provider to continue.</p>

          {/* tabs */}
          <div style={{
            display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 4,
            background: "rgba(255,255,255,.04)", border: "1px solid rgba(255,255,255,.08)",
            padding: 4, borderRadius: 14, marginBottom: 22,
          }}>
            {[["xtream","Xtream"],["m3u","M3U URL"],["companion","Phone Pair"]].map(([id, label]) => (
              <button key={id} onClick={() => setTab(id)} style={{
                padding: "10px 8px", borderRadius: 10, border: "none", cursor: "pointer",
                background: tab === id ? "linear-gradient(135deg, #D4AF37, #B8932A)" : "transparent",
                color: tab === id ? "#1A1407" : "var(--ink-300)",
                fontSize: 13, fontWeight: 700, letterSpacing: ".02em",
                boxShadow: tab === id ? "0 4px 14px rgba(212,175,55,.35)" : "none",
                transition: "all .25s var(--ease-out)",
              }}>{label}</button>
            ))}
          </div>

          {tab === "xtream" && (
            <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
              <Field label="Server URL" placeholder="https://my.provider.com:8080" icon="globe" />
              <Field label="Username" placeholder="your-username" icon="user" />
              <Field label="Password" placeholder="••••••••••••" icon="shield" type="password" />
              <label style={{ display: "flex", alignItems: "center", gap: 10, fontSize: 13, color: "var(--ink-300)", marginTop: 4 }}>
                <span style={{
                  width: 18, height: 18, borderRadius: 4, border: "1.5px solid var(--gold-500)",
                  background: "linear-gradient(135deg, #D4AF37, #B8932A)", display: "grid", placeItems: "center",
                }}><Icon name="check" size={12} color="#1A1407" strokeWidth={3} /></span>
                Remember this device
              </label>
            </div>
          )}
          {tab === "m3u" && (
            <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
              <Field label="M3U Playlist URL" placeholder="https://.../playlist.m3u" icon="globe" />
              <Field label="EPG (XMLTV) URL — optional" placeholder="https://.../epg.xml" icon="info" />
            </div>
          )}
          {tab === "companion" && (
            <div style={{ textAlign: "center", padding: "8px 0 4px" }}>
              <div style={{
                width: 180, height: 180, margin: "0 auto",
                background: "linear-gradient(135deg, #1F2433, #0B0E15)",
                border: "1px solid rgba(212,175,55,.35)",
                borderRadius: 18, display: "grid", placeItems: "center",
                boxShadow: "0 0 40px rgba(212,175,55,.18) inset, 0 12px 30px rgba(0,0,0,.4)",
              }}>
                <Icon name="qr" size={120} color="var(--gold-300)" strokeWidth={1.4} />
              </div>
              <div style={{ marginTop: 18, fontSize: 14, color: "var(--ink-300)" }}>Scan with the Derbix companion app</div>
              <div style={{ marginTop: 14, fontFamily: "var(--font-mono)", fontSize: 28, letterSpacing: ".3em", color: "var(--gold-300)", fontWeight: 700 }}>847 · 213</div>
            </div>
          )}

          <button className="btn btn-primary" style={{ width: "100%", marginTop: 22, padding: "16px 22px", fontSize: 15 }}>
            <Icon name="arrowR" size={16} color="#1A1407" strokeWidth={2.6} />
            <span>Continue</span>
          </button>
          <div style={{ display: "flex", justifyContent: "space-between", marginTop: 18, fontSize: 13, color: "var(--ink-300)" }}>
            <span style={{ cursor: "pointer" }}>Need help?</span>
            <span style={{ cursor: "pointer", color: "var(--gold-300)" }}>Restore subscription</span>
          </div>
        </div>
      </div>
    </div>
  );
}

function Field({ label, placeholder, icon, type = "text" }) {
  return (
    <label style={{ display: "flex", flexDirection: "column", gap: 6 }}>
      <span style={{ fontSize: 12, fontWeight: 600, letterSpacing: ".08em", textTransform: "uppercase", color: "var(--ink-300)" }}>{label}</span>
      <div style={{
        display: "flex", alignItems: "center", gap: 10,
        padding: "12px 14px", borderRadius: 12,
        background: "rgba(255,255,255,.04)",
        border: "1px solid rgba(255,255,255,.10)",
        transition: "all .2s var(--ease-out)",
      }}>
        {icon && <Icon name={icon} size={16} color="var(--ink-400)" />}
        <input type={type} placeholder={placeholder} defaultValue="" style={{
          flex: 1, background: "transparent", border: "none", outline: "none",
          color: "var(--ink-100)", fontSize: 14, fontFamily: "inherit",
        }} />
      </div>
    </label>
  );
}

/* =====================================================
   COMPANION SETUP / PAIRING
   ===================================================== */
function CompanionScreen() {
  return (
    <div className="app-bg" style={{ width: "100%", height: "100%", display: "grid", gridTemplateColumns: "1fr", position: "relative" }}>
      <AmbientBloom />
      <div style={{ display: "grid", placeItems: "center", padding: 60, zIndex: 2 }}>
        <div className="glass-strong" style={{ maxWidth: 1080, width: "100%", padding: 56, borderRadius: 28, boxShadow: "var(--shadow-float)" }}>
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 36 }}>
            <div>
              <div className="t-eyebrow">Step 2 of 3</div>
              <h2 style={{ margin: "8px 0 6px", fontSize: 36, fontWeight: 800, letterSpacing: "-0.02em" }}>Pair your phone</h2>
              <p style={{ margin: 0, color: "var(--ink-300)", fontSize: 15 }}>Use the Derbix companion app to type, search, and cast — without the remote.</p>
            </div>
            <Wordmark size={16} />
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 32 }}>
            <div style={{
              background: "linear-gradient(160deg, rgba(212,175,55,.10), rgba(11,14,21,.6))",
              border: "1px solid rgba(212,175,55,.22)",
              borderRadius: 22, padding: 32,
            }}>
              <div style={{ display: "flex", justifyContent: "center" }}>
                <div style={{
                  width: 280, height: 280, borderRadius: 22,
                  background: "linear-gradient(135deg, #F7E7B5, #ffffff)",
                  display: "grid", placeItems: "center",
                  boxShadow: "0 0 50px rgba(212,175,55,.4), 0 24px 60px rgba(0,0,0,.45)",
                }}>
                  <Icon name="qr" size={210} color="#0B0E15" strokeWidth={1.2} />
                </div>
              </div>
              <div style={{ textAlign: "center", marginTop: 22 }}>
                <div style={{ fontSize: 13, color: "var(--ink-300)", marginBottom: 8 }}>or enter this code on your phone</div>
                <div style={{ display: "inline-flex", gap: 8 }}>
                  {["8","4","7","2","1","3"].map((d, i) => (
                    <span key={i} style={{
                      fontFamily: "var(--font-mono)", fontWeight: 800, fontSize: 32,
                      color: "var(--gold-300)", padding: "8px 14px",
                      background: "rgba(212,175,55,.10)", border: "1px solid rgba(212,175,55,.30)",
                      borderRadius: 10,
                    }}>{d}</span>
                  ))}
                </div>
              </div>
            </div>
            <div>
              <h3 style={{ margin: "0 0 18px", fontSize: 22, fontWeight: 700 }}>How it works</h3>
              <ol style={{ margin: 0, padding: 0, listStyle: "none", display: "flex", flexDirection: "column", gap: 14 }}>
                {[
                  ["Download Derbix Mobile", "From the App Store or Play Store"],
                  ["Open the app and tap 'Pair TV'", "It will ask for camera permission"],
                  ["Scan the QR or type the 6‑digit code", "Pairing completes in a couple of seconds"],
                  ["Type, search, cast — from your phone", "Your library now follows you everywhere"],
                ].map(([t, sub], i) => (
                  <li key={i} style={{ display: "flex", gap: 14, alignItems: "flex-start" }}>
                    <span style={{
                      width: 32, height: 32, borderRadius: "50%",
                      display: "grid", placeItems: "center", flexShrink: 0,
                      background: i === 0 ? "linear-gradient(135deg, #D4AF37, #8C6E1F)" : "rgba(255,255,255,.06)",
                      color: i === 0 ? "#1A1407" : "var(--ink-300)",
                      fontWeight: 800, fontSize: 14,
                      border: i === 0 ? "none" : "1px solid rgba(255,255,255,.08)",
                    }}>{i + 1}</span>
                    <div>
                      <div style={{ fontSize: 15, fontWeight: 600 }}>{t}</div>
                      <div style={{ fontSize: 13, color: "var(--ink-400)", marginTop: 2 }}>{sub}</div>
                    </div>
                  </li>
                ))}
              </ol>
              <div style={{ display: "flex", gap: 12, marginTop: 32 }}>
                <button className="btn btn-secondary">Skip for now</button>
                <button className="btn btn-primary"><Icon name="arrowR" size={14} color="#1A1407" strokeWidth={2.6}/> <span>I've paired it</span></button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

window.SCREENS = Object.assign(window.SCREENS || {}, { SplashScreen, LoginScreen, CompanionScreen });

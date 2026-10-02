/** A poster tile. A missing or broken image falls back to the title on a plain card, never a broken icon. */
export function Poster({ src, title, sub, progress, fav }: { src: string; title: string; sub?: string; progress?: number; fav?: boolean }) {
  return (
    <div className="poster">
      <div className="poster-img">
        <div className="poster-fallback">{title}</div>
        {src && <img src={src} alt="" loading="lazy" onError={(e) => { e.currentTarget.style.display = 'none' }} />}
        {fav && <span className="poster-fav">★</span>}
        {progress !== undefined && progress > 0 && <div className="poster-progress"><div style={{ width: `${Math.round(progress * 100)}%` }} /></div>}
      </div>
      <div className="poster-title">{title}</div>
      {sub && <div className="poster-sub">{sub}</div>}
    </div>
  )
}

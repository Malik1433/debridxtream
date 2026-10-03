import { useEffect, useRef } from 'react'

/**
 * A poster softened into a backdrop WITHOUT a CSS filter (W4 QA R6: a full-screen blur(24px) halved
 * Home's frame rate on the Samsung). The poster is drawn once into a tiny canvas (cover-cropped) and
 * the canvas is stretched by CSS: bilinear scaling is the blur, and it costs nothing per frame. The
 * image is never read back, so a poster without CORS headers is fine.
 */
export function SoftBackdrop({ src, className, w = 48, h = 18 }: { src: string; className: string; w?: number; h?: number }) {
  const ref = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const c = ref.current
    if (!c || !src) return
    const img = new Image()
    let live = true
    img.onload = () => {
      const g = live ? c.getContext('2d') : null
      if (!g || !img.width || !img.height) return
      const k = Math.max(w / img.width, h / img.height)
      const sw = w / k, sh = h / k
      g.clearRect(0, 0, w, h)
      g.drawImage(img, (img.width - sw) / 2, (img.height - sh) / 2, sw, sh, 0, 0, w, h)
    }
    img.src = src
    return () => { live = false; img.onload = null }
  }, [src, w, h])
  return <canvas ref={ref} width={w} height={h} className={className} />
}

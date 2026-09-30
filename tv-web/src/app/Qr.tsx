import qrcode from 'qrcode-generator'
import { useMemo } from 'react'

/** A QR the phone camera reads from 3 m: level M, a real quiet zone, drawn as SVG (sharp at any scale). */
export function Qr({ text, size }: { text: string; size: number }) {
  const svg = useMemo(() => {
    const qr = qrcode(0, 'M')
    qr.addData(text)
    qr.make()
    return qr.createSvgTag({ cellSize: 8, margin: 4, scalable: true })
  }, [text])
  return <div className="qr" style={{ width: size, height: size }} dangerouslySetInnerHTML={{ __html: svg }} />
}

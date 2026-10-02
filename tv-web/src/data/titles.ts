/**
 * Port of Android's MediaTitleCleaner + VodAdapter.parseVodCardText: the human title and the
 * quality badge a poster card shows. Owner rule: same cards as Android.
 */
const LEADING_TAG_BLOCKS = /^(?:\s*\|[^|]*\|\s*)+/
const PIPE_TAG_ANYWHERE = /\s*\|\s*[A-Za-z0-9]{1,6}\s*\|\s*/g
const LEADING_SITE = /^\s*www[^\-–—]{0,60}[\-–—]\s*/i
const LEADING_BRACKET = /^\s*[[(][^\])]{0,40}[\])]\s*/
const LEADING_SEPARATORS = /^[\s\-–—·:|]+/
const TRAILING_SEPARATORS = /[\s\-–—·:|]+$/
const MULTI_WHITESPACE = /\s{2,}/g

export function cleanTitle(raw: string | null | undefined): string {
  if (!raw || !raw.trim()) return ''
  let name = raw.trim().replace(/\\'/g, "'").replace(/\\"/g, '"').replace(/\\\//g, '/')
  name = name.replace(LEADING_SITE, '')
  let prev: string
  do { prev = name; name = name.replace(LEADING_BRACKET, '').trim() } while (name !== prev)
  name = name.replace(LEADING_TAG_BLOCKS, ' ')
  name = name.replace(PIPE_TAG_ANYWHERE, ' ')
  name = name.replace(LEADING_SEPARATORS, '').replace(TRAILING_SEPARATORS, '').replace(MULTI_WHITESPACE, ' ').trim()
  return name
}

const LANG_TAG = /[|[(]?\s*(MULTI|EN|FR|IT|ES|DE|RU|TR|AR|NL|PT|PL)\s*[\])]?$/i
const QUALITY_TAG = /[|[(]?\s*(4K|UHD|1080p|720p|FHD|HD)\s*[\])]?$/i
const dropSep = (n: string) => (n.endsWith('|') || n.endsWith('-') ? n.slice(0, -1).trim() : n)

export function cardText(rawName: string | null | undefined): { title: string; quality: '4K' | 'FHD' | 'HD' } {
  let name = (rawName ?? '').trim() || 'Unknown'
  const lang = LANG_TAG.exec(name)
  if (lang) name = dropSep(name.replace(lang[0], '').trim())
  let quality: '4K' | 'FHD' | 'HD'
  const q = QUALITY_TAG.exec(name)
  if (q) {
    const v = q[1].toUpperCase()
    quality = v.includes('4K') || v.includes('UHD') ? '4K' : v.includes('1080') || v.includes('FHD') ? 'FHD' : 'HD'
    name = dropSep(name.replace(q[0], '').trim())
  } else {
    const l = name.toLowerCase()
    quality = l.includes('4k') || l.includes('uhd') ? '4K' : l.includes('1080p') || l.includes('fhd') ? 'FHD' : 'HD'
  }
  return { title: cleanTitle(name) || 'Unknown', quality }
}

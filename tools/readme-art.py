#!/usr/bin/env python3
"""Builds the animated README / website art from the real screenshots in docs/screenshots.

    python3 tools/readme-art.py          (macOS: uses `sips` to resize, no extra packages)

Writes docs/readme/hero.svg and docs/readme/showcase-{tv,phone}.{en,id,zh}.svg. Screenshots are embedded as data URIs,
because GitHub shows README SVGs as <img>, which can't load other files. Animations are plain CSS, so they play on
GitHub, in browsers and on the website. Re-run after updating the screenshots."""
import base64, os, subprocess, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(ROOT, "docs", "screenshots")
OUT = os.path.join(ROOT, "docs", "readme")

TV = ["v2/02-beranda.jpg", "v2/03-hero-aksi.jpg", "v2/04-launchpad.jpg", "v2/06-pusat-kontrol.jpg", "v2/07-asisten.jpg", "v2/01-profil.jpg"]
PHONE = ["mobile-v4/01-beranda.jpg", "mobile-v4/04-tv-live.jpg", "mobile-v4/03-halaman-saluran.jpg", "mobile-v4/02-mini-player.jpg"]

CAPTIONS = {
    "en": {"tv": ["Home", "Hero + actions", "Launchpad", "Control Center", "Assistant", "Who's watching?"],
           "phone": ["Home", "Live TV", "Channel page", "Mini player"]},
    "id": {"tv": ["Beranda", "Hero + aksi", "Launchpad", "Pusat Kontrol", "Asisten", "Siapa yang menonton?"],
           "phone": ["Beranda", "TV Live", "Halaman saluran", "Mini player"]},
    "zh": {"tv": ["主屏幕", "精选与操作", "启动台", "控制中心", "助手", "谁在观看？"],
           "phone": ["主页", "电视直播", "频道页", "迷你播放器"]},
}

FONT = "font-family=\"Outfit, -apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif\""


def jpeg(rel, width, quality=62):
    """The screenshot resized to [width], as a data URI."""
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "x.jpg")
        subprocess.run(["sips", "-s", "format", "jpeg", "-s", "formatOptions", str(quality), "--resampleWidth", str(width),
                        os.path.join(SHOTS, rel), "--out", out], check=True, capture_output=True)
        with open(out, "rb") as f:
            return "data:image/jpeg;base64," + base64.b64encode(f.read()).decode()


def crossfade(cls, n, step):
    """CSS that shows n stacked images one after another, [step] seconds each, with a soft fade."""
    total = n * step
    on = 100 / n
    fade = min(4.0, on / 4)
    return (f".{cls}{{opacity:0;transform-box:fill-box;transform-origin:center;animation:{cls} {total}s infinite}}"
            f"@keyframes {cls}{{0%{{opacity:0;transform:scale(1.025)}}{fade:.2f}%{{opacity:1}}"
            f"{on:.2f}%{{opacity:1;transform:scale(1)}}{on + fade:.2f}%{{opacity:0}}100%{{opacity:0}}}}"
            + "".join(f".{cls}:nth-of-type({i + 1}){{animation-delay:{i * step - 0.01:.2f}s}}" for i in range(n)))


BG = """<defs>
<radialGradient id="g1" cx="15%" cy="15%" r="60%"><stop offset="0" stop-color="#2a5ae6" stop-opacity=".85"/><stop offset="1" stop-color="#2a5ae6" stop-opacity="0"/></radialGradient>
<radialGradient id="g2" cx="90%" cy="20%" r="55%"><stop offset="0" stop-color="#7a32dc" stop-opacity=".75"/><stop offset="1" stop-color="#7a32dc" stop-opacity="0"/></radialGradient>
<radialGradient id="g3" cx="55%" cy="110%" r="60%"><stop offset="0" stop-color="#e6468f" stop-opacity=".55"/><stop offset="1" stop-color="#e6468f" stop-opacity="0"/></radialGradient>
<linearGradient id="glass" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".22"/><stop offset="1" stop-color="#fff" stop-opacity=".06"/></linearGradient>
<linearGradient id="bezel" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#3a3d46"/><stop offset=".5" stop-color="#15161b"/><stop offset="1" stop-color="#2c2f37"/></linearGradient>
<linearGradient id="sheen" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".16"/><stop offset=".35" stop-color="#fff" stop-opacity="0"/></linearGradient>
<filter id="shadow" x="-20%" y="-20%" width="140%" height="160%"><feDropShadow dx="0" dy="24" stdDeviation="22" flood-color="#000" flood-opacity=".55"/></filter>
%CLIPS%
</defs>
<rect width="100%" height="100%" fill="#07080D"/>
<g class="blobs"><rect x="-10%" y="-10%" width="120%" height="120%" fill="url(#g1)" class="b1"/><rect x="-10%" y="-10%" width="120%" height="120%" fill="url(#g2)" class="b2"/><rect x="-10%" y="-10%" width="120%" height="120%" fill="url(#g3)" class="b3"/></g>"""

BG_CSS = (".b1{animation:drift 14s ease-in-out infinite alternate}.b2{animation:drift 18s ease-in-out infinite alternate-reverse}"
          ".b3{animation:drift 22s ease-in-out infinite alternate}"
          "@keyframes drift{0%{transform:translate(0,0)}100%{transform:translate(40px,-24px)}}"
          ".float{animation:float 6s ease-in-out infinite}.float2{animation:float 7s ease-in-out infinite reverse}"
          "@keyframes float{0%,100%{transform:translateY(0)}50%{transform:translateY(-10px)}}"
          "@media (prefers-reduced-motion:reduce){*{animation:none!important}.in{opacity:1}.t:first-of-type,.p:first-of-type{opacity:1}}")


def tv(x, y, w, images, cls, clip):
    """A TV with a thin bezel and a stand; [images] crossfade on its screen."""
    h = round(w * 9 / 16)
    pad = 10
    stack = "".join(f'<image class="{cls}" href="{src}" x="{x}" y="{y}" width="{w}" height="{h}" preserveAspectRatio="xMidYMid slice"/>'
                    for src in images)
    return (f'<g filter="url(#shadow)"><rect x="{x - pad}" y="{y - pad}" width="{w + 2 * pad}" height="{h + 2 * pad}" rx="18" fill="url(#bezel)"/>'
            f'<path d="M{x + w / 2 - 70} {y + h + pad} h140 l26 34 h-192 z" fill="#1c1e24"/>'
            f'<rect x="{x + w / 2 - 130}" y="{y + h + pad + 32}" width="260" height="8" rx="4" fill="#2a2d35"/></g>'
            f'<g clip-path="url(#{clip})"><rect x="{x}" y="{y}" width="{w}" height="{h}" fill="#000"/>{stack}'
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="url(#sheen)"/></g>'), (x, y, w, h)


def phone(x, y, w, images, cls, clip):
    """A phone with rounded corners and a punch-hole camera; [images] crossfade on its screen."""
    h = round(w * 20 / 9)
    pad = 9
    stack = "".join(f'<image class="{cls}" href="{src}" x="{x}" y="{y}" width="{w}" height="{h}" preserveAspectRatio="xMidYMid slice"/>'
                    for src in images)
    return (f'<g filter="url(#shadow)"><rect x="{x - pad}" y="{y - pad}" width="{w + 2 * pad}" height="{h + 2 * pad}" rx="{38}" fill="url(#bezel)"/></g>'
            f'<g clip-path="url(#{clip})"><rect x="{x}" y="{y}" width="{w}" height="{h}" fill="#000"/>{stack}'
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="url(#sheen)"/></g>'
            f'<circle cx="{x + w / 2}" cy="{y + 14}" r="5" fill="#000"/>'), (x, y, w, h)


def chip(x, y, text, dot=None, cls="float"):
    """A floating glass pill, like the app's notifications."""
    w = 26 + len(text) * 8.6 + (16 if dot else 0)
    d = f'<circle cx="{x + 18}" cy="{y + 18}" r="5" fill="{dot}"></circle>' if dot else ""
    tx = x + (32 if dot else 14)
    return (f'<g class="{cls}"><rect x="{x}" y="{y}" width="{w:.0f}" height="36" rx="18" fill="url(#glass)" stroke="#fff" stroke-opacity=".22"/>'
            f'{d}<text x="{tx}" y="{y + 23.5}" {FONT} font-size="15" font-weight="600" fill="#F5F5F7">{text}</text></g>')


def svg(w, h, css, clips, body, title):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" role="img">'
            f'<title>{title}</title><style>{css}{BG_CSS}</style>' + BG.replace("%CLIPS%", clips) + body + "</svg>")


def clip(cid, rect, r):
    x, y, w, h = rect
    return f'<clipPath id="{cid}"><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}"/></clipPath>'


def hero():
    tv_imgs = [jpeg(p, 860) for p in TV[:5]]
    ph_imgs = [jpeg(p, 230) for p in PHONE]
    tv_svg, tv_rect = tv(110, 92, 820, tv_imgs, "t", "ctv")
    ph_svg, ph_rect = phone(960, 196, 210, ph_imgs, "p", "cph")
    mark = ('<g transform="translate(110 30) scale(1.4)" fill="none" stroke="#F5F5F7" stroke-linecap="round" stroke-linejoin="round" stroke-width="2">'
            '<path d="M6.5,4 h11 a4.5,4.5 0 0 1 4.5,4.5 v7 a4.5,4.5 0 0 1 -4.5,4.5 h-11 a4.5,4.5 0 0 1 -4.5,-4.5 v-7 a4.5,4.5 0 0 1 4.5,-4.5 z M9,22.5 h6"/>'
            '<path d="M5.8,13.6 L9.4,8.8 L11.9,11.6 L14.4,8.4 L18.2,13.6"/><circle cx="18" cy="7.6" r="1.4" fill="#64D2FF" stroke="none"/></g>'
            f'<text x="152" y="58" {FONT} font-size="26" font-weight="700" fill="#F5F5F7">Tahoe TV</text>')
    body = (mark + tv_svg + f'<g class="float2">{ph_svg}</g>'
            + chip(820, 40, "LIVE · 1,000+ channels", "#FF453A")
            + chip(40, 600, "Android TV · Phone · macOS · Windows", None, "float2")
            + chip(640, 646, "Open source · MIT", "#30D158"))
    css = crossfade("t", len(tv_imgs), 3.2) + crossfade("p", len(ph_imgs), 4)
    clips = clip("ctv", tv_rect, 8) + clip("cph", ph_rect, 30)
    return svg(1280, 720, css, clips, body, "Tahoe TV on a TV and a phone")


def showcase_tv(lang):
    imgs = [jpeg(p, 560) for p in TV]
    cap = CAPTIONS[lang]["tv"]
    body, clips, css = "", "", ""
    for i, src in enumerate(imgs):
        col, row = i % 3, i // 3
        x, y, w = 56 + col * 400, 70 + row * 290, 368
        h = round(w * 9 / 16)
        clips += clip(f"s{i}", (x, y, w, h), 14)
        body += (f'<g class="in i{i}"><g class="float{"" if i % 2 else "2"}">'
                 f'<rect x="{x - 6}" y="{y - 6}" width="{w + 12}" height="{h + 12}" rx="18" fill="url(#glass)" stroke="#fff" stroke-opacity=".18" filter="url(#shadow)"/>'
                 f'<image href="{src}" x="{x}" y="{y}" width="{w}" height="{h}" clip-path="url(#s{i})" preserveAspectRatio="xMidYMid slice"/>'
                 f'<text x="{x + 4}" y="{y + h + 36}" {FONT} font-size="17" font-weight="600" fill="#F5F5F7">{cap[i]}</text></g></g>')
        css += f".i{i}{{animation-delay:{i * 0.12:.2f}s}}"
    # the shorthand first, so the per-panel delays after it win (staggered fade-in)
    css = ".in{opacity:0;animation:in .8s ease-out forwards}@keyframes in{from{opacity:0;transform:translateY(16px)}to{opacity:1;transform:none}}" + css
    return svg(1280, 660, css, clips, body, "Tahoe TV screens")


def showcase_phone(lang):
    imgs = [jpeg(p, 300) for p in PHONE]
    cap = CAPTIONS[lang]["phone"]
    body, clips, css = "", "", ""
    for i, src in enumerate(imgs):
        w = 230
        x, y = 80 + i * 290, 60 + (0 if i % 2 else 26)
        h = round(w * 20 / 9)
        clips += clip(f"m{i}", (x, y, w, h), 30)
        body += (f'<g class="in i{i}"><g class="float{"" if i % 2 else "2"}">'
                 f'<rect x="{x - 9}" y="{y - 9}" width="{w + 18}" height="{h + 18}" rx="38" fill="url(#bezel)" filter="url(#shadow)"/>'
                 f'<image href="{src}" x="{x}" y="{y}" width="{w}" height="{h}" clip-path="url(#m{i})" preserveAspectRatio="xMidYMid slice"/>'
                 f'<circle cx="{x + w / 2}" cy="{y + 14}" r="5" fill="#000"/>'
                 f'<text x="{x + w / 2}" y="{y + h + 48}" text-anchor="middle" {FONT} font-size="17" font-weight="600" fill="#F5F5F7">{cap[i]}</text></g></g>')
        css += f".i{i}{{animation-delay:{i * 0.15:.2f}s}}"
    # the shorthand first, so the per-panel delays after it win (staggered fade-in)
    css = ".in{opacity:0;animation:in .8s ease-out forwards}@keyframes in{from{opacity:0;transform:translateY(16px)}to{opacity:1;transform:none}}" + css
    return svg(1280, 720, css, clips, body, "Tahoe TV phone app")


def write(name, text):
    with open(os.path.join(OUT, name), "w") as f:
        f.write(text)
    print(f"{name}: {len(text) // 1024} KB")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    write("hero.svg", hero())
    for lang in ("en", "id", "zh"):
        write(f"showcase-tv.{lang}.svg", showcase_tv(lang))
        write(f"showcase-phone.{lang}.svg", showcase_phone(lang))

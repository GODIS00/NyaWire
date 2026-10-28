"""Regenerate the small vector launcher collection. No raster copies or build dependency."""
from pathlib import Path

RES = Path(__file__).resolve().parents[1] / "app/src/main/res"
PALETTES = [
    ("sakura", "#30142D", "#7D285B", "#FF8CC9", "heart"),
    ("midnight", "#10172E", "#343367", "#C1B8FF", "sleep"),
    ("mint", "#10312D", "#246557", "#80E5C1", "smile"),
    ("ocean", "#102A49", "#245C91", "#8BCBFF", "open"),
    ("sunset", "#45213A", "#B6584B", "#FFBE8D", "wink"),
    ("lavender", "#2D224A", "#63508B", "#D0B8FF", "open"),
    ("peach", "#F7D6C8", "#ECA5AA", "#CF557F", "smile"),
    ("frost", "#D6F2F5", "#9BC5DE", "#377B9B", "wink"),
    ("amoled", "#000000", "#0E0E12", "#D3D5E0", "sleep"),
]

def vector(paths):
    return ('<vector xmlns:android="http://schemas.android.com/apk/res/android" '
            'xmlns:aapt="http://schemas.android.com/aapt" android:width="108dp" android:height="108dp" '
            'android:viewportWidth="108" android:viewportHeight="108">\n' + paths + '\n</vector>\n')

def path(data, fill, stroke=None, width=1.5):
    attrs = f' android:strokeColor="{stroke}" android:strokeWidth="{width}" android:strokeLineCap="round" android:strokeLineJoin="round"' if stroke else ''
    return f'    <path android:fillColor="{fill}"{attrs} android:pathData="{data}" />\n'

def generate():
    for name, bg, end, accent, expression in PALETTES:
        backdrop = '''    <path android:pathData="M0,0H108V108H0Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="16" android:startY="0" android:endX="92" android:endY="108"
                android:startColor="%s" android:endColor="%s" />
        </aapt:attr>
    </path>''' % (bg, end)
        (RES / f"drawable/icon_{name}_background.xml").write_text(vector(backdrop), encoding="utf-8")
        p = path("M43,84A32,32 0,1 1,65,84", "#00000000", accent, 1.5)
        p += path("M28,47Q27,35 29,27Q39,29 44,38Q54,34 64,38Q71,29 79,27Q81,35 80,47L84,56L80,57L84,63L78,64Q74,77 54,78Q34,77 30,64L24,63L28,57L24,56Z", "#FFF7FE")
        p += path("M31,32L33,44L40,39Z M77,32L75,44L68,39Z", accent)
        # A small tuft echoes the original mascot without details that vanish at launcher size.
        p += path("M46,39L50,32L55,37L61,33L61,40Z", "#FFF7FE")
        ink = "#30243C"
        if expression == "heart":
            p += path("M36,52C36,48 42,47 44,51C46,47 52,48 52,52C52,56 44,61 44,61C44,61 36,56 36,52Z M56,52C56,48 62,47 64,51C66,47 72,48 72,52C72,56 64,61 64,61C64,61 56,56 56,52Z", accent)
        elif expression in ("smile", "sleep"):
            eyes = "M37,54Q43,48 49,54 M59,54Q65,48 71,54" if expression == "smile" else "M37,52Q43,58 49,52 M59,52Q65,58 71,52"
            p += path(eyes, "#00000000", ink, 2.6)
        else:
            p += path("M38,49Q43,47 48,50L47,57Q43,61 39,57Z", ink)
            p += path("M40,50H43V54H40Z", accent)
            p += path("M60,54Q65,49 70,54" if expression == "wink" else "M60,50Q65,47 70,49L69,57Q65,61 61,57Z", "#00000000" if expression == "wink" else ink, ink if expression == "wink" else None, 2.6)
            if expression != "wink":
                p += path("M62,50H65V54H62Z", accent)
        p += path("M51,61Q54,59 57,61L54,64Z", ink)
        p += path("M54,64Q50,69 46,65 M54,64Q58,69 62,65", "#00000000", ink, 1.7)
        p += path("M34,61L37,59 M37,64L40,62 M68,62L71,60 M71,65L74,63", "#00000000", accent, 1.8)
        p += path("M48,81Q54,77 60,81 M51,84Q54,82 57,84", "#00000000", accent, 1.8)
        p += path("M55.2,87A1.2,1.2 0,1 1,52.8,87A1.2,1.2 0,1 1,55.2,87", accent)
        (RES / f"drawable/icon_{name}_foreground.xml").write_text(vector(p), encoding="utf-8")
        adaptive = f'''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/icon_{name}_background" />
    <foreground android:drawable="@drawable/icon_{name}_foreground" />
'''
        (RES / f"mipmap-anydpi-v26/ic_launcher_{name}.xml").write_text(adaptive + '</adaptive-icon>\n', encoding="utf-8")
        (RES / "mipmap-anydpi-v33").mkdir(exist_ok=True)
        mono = '    <monochrome android:drawable="@drawable/ic_vpn_notification" />\n'
        (RES / f"mipmap-anydpi-v33/ic_launcher_{name}.xml").write_text(adaptive + mono + '</adaptive-icon>\n', encoding="utf-8")
    original = (RES / "mipmap-anydpi-v26/ic_launcher.xml").read_text(encoding="utf-8")
    (RES / "mipmap-anydpi-v33/ic_launcher.xml").write_text(original.replace('</adaptive-icon>', '    <monochrome android:drawable="@drawable/ic_vpn_notification" />\n</adaptive-icon>'), encoding="utf-8")

if __name__ == "__main__":
    generate()

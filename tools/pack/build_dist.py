"""Turn tools/build/out/* into a static site: engine packs, per-model PPDs and catalogue.json.

dist/
  catalogue.json
  packs/<engine>-<version>-<abi>.zip     libfpo_<engine>.so, driver.json, engine-wide data
  ppd/<engine>/<file>.ppd.gz             one gzip per printer model

Run:  python -I tools/pack/build_dist.py [--base-url https://example.github.io/free-printing-otg]
"""
import argparse
import gzip
import hashlib
import io
import json
import re
import shutil
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "tools" / "build" / "out"
DIST = ROOT / "dist"
ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64"]

ENGINES = {
    # engine: (version, runtime env for the filter)
    "splix": ("2.0.0-svn315", {}),
    "escpr": ("1.8.8", {}),
    "hplip": ("3.25.6", {}),
    "gutenprint": ("5.3.5", {"STP_DATA_PATH": "${PACK}/data/xml"}),
}


def sha256(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def gz(b: bytes) -> bytes:
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0, compresslevel=9) as f:
        f.write(b)
    return buf.getvalue()


def ppd_meta(text: str) -> dict:
    def first(key):
        m = re.search(r'^\*' + key + r':\s*"([^"]*)"', text, re.M)
        return m.group(1) if m else None

    did = first("1284DeviceID") or ""
    kv = {}
    for part in did.split(";"):
        if ":" in part:
            k, v = part.split(":", 1)
            kv[k.strip().upper()] = v.strip()
    return {
        "manufacturer": first("Manufacturer"),
        "model": first("ModelName"),
        "nickname": first("NickName"),
        "language": first("LanguageVersion"),
        "mfg": kv.get("MFG") or kv.get("MANUFACTURER"),
        "mdl": kv.get("MDL") or kv.get("MODEL"),
        "cmd": [c for c in (kv.get("CMD") or kv.get("COMMAND SET") or "").split(",") if c],
    }


def collect_ppds():
    """Yield (engine, source file name, ppd bytes)."""
    sources = {
        "splix": (OUT / "arm64-v8a" / "splix-data" / "ppd", "*.ppd"),
        "escpr": (OUT / "arm64-v8a" / "escpr-data" / "ppd", "*.ppd"),
        "hplip": (OUT / "hplip-ppd", "*.ppd"),
        "gutenprint": (OUT / "gutenprint-ppd", "*.ppd.gz"),
    }
    for engine, (d, pat) in sources.items():
        for f in sorted(d.glob(pat)):
            raw = f.read_bytes()
            if f.suffix == ".gz":
                raw = gzip.decompress(raw)
            text = raw.decode("latin-1")
            if engine == "hplip":
                text = text.replace("@VERSION@", ENGINES["hplip"][0])
            yield engine, f.name.removesuffix(".gz"), text


# Generic entries: upstream ships no model-less PPD for these, so derive one from a mainstream model of the same
# engine (same filter and options) and give it a generic identity. Matched by IEEE-1284 CMD when no model matches.
GENERIC = [
    # engine, base ppd (in the engine's PPD dir), file, display name, cmd, nickname suffix
    ("escpr", "Epson-XP-420_Series-epson-escpr-en.ppd", "Generic-Epson_ESC_P-R.ppd", "Generic Epson ESC/P-R",
     ["ESCPR1", "ESC/P-R"]),
    ("hplip", "hp-color_laserjet_2500_series.ppd", "Generic-PCL_5c_PCL_XL_Printer.ppd",
     "Generic PCL 5c/PCL XL Printer", ["PCL", "PCLXL", "PCL5C"]),
    ("hplip", "hp-laserjet_1200-pcl3.ppd", "Generic-PCL_5e_PCL_XL_Printer.ppd",
     "Generic PCL 5e/PCL XL Printer", ["PCL", "PCLXL", "PCL5E"]),
]
GENERIC_PS = """*PPD-Adobe: "4.3"
*FormatVersion: "4.3"
*FileVersion: "1.0"
*LanguageVersion: English
*LanguageEncoding: ISOLatin1
*PCFileName: "GENERPS2.PPD"
*Manufacturer: "Generic"
*Product: "(Generic PostScript Level 2)"
*ModelName: "Generic PostScript Level 2"
*ShortNickName: "Generic PostScript Level 2"
*NickName: "Generic PostScript Level 2 (sent as PostScript, no driver pack)"
*PSVersion: "(2017.000) 0"
*LanguageLevel: "2"
*ColorDevice: True
*DefaultColorSpace: RGB
*FileSystem: False
*Throughput: "1"
*TTRasterizer: Type42
*1284DeviceID: "MFG:Generic;MDL:PostScript Level 2;CMD:POSTSCRIPT,PS;"
*OpenUI *PageSize: PickOne
*DefaultPageSize: A4
*PageSize A4/A4: "<</PageSize[595 842]>>setpagedevice"
*PageSize Letter/Letter: "<</PageSize[612 792]>>setpagedevice"
*PageSize Legal/Legal: "<</PageSize[612 1008]>>setpagedevice"
*CloseUI: *PageSize
*DefaultImageableArea: A4
*ImageableArea A4: "12 12 583 830"
*ImageableArea Letter: "12 12 600 780"
*ImageableArea Legal: "12 12 600 996"
*DefaultPaperDimension: A4
*PaperDimension A4: "595 842"
*PaperDimension Letter: "612 792"
*PaperDimension Legal: "612 1008"
"""


# SpliX models NokoPrint lists whose PPD is not in the upstream snapshot: same SPL family as a sibling that is.
# Flagged experimental in the catalogue until someone prints on the real device.
SPLIX_ALIASES = [
    ("m2020.ppd", ["Samsung M2070", "Samsung M2670", "Samsung M2870"]),
    ("ml1660.ppd", ["Samsung ML-1670", "Samsung ML-1671", "Samsung ML-1676", "Samsung ML-1860",
                    "Samsung ML-1861", "Samsung ML-1865W", "Samsung ML-1866W"]),
    ("ml1915.ppd", ["Samsung ML-1911", "Samsung ML-1916", "Samsung ML-2520", "Samsung ML-2526"]),
    ("ml3310.ppd", ["Samsung ML-331x"]),
    ("scx4623f.ppd", ["Samsung SCX-4623"]),
    ("scx4521f.ppd", ["Samsung SCX-4021", "Samsung SCX-4321", "Samsung SCX-4521", "Samsung SCX-4650",
                      "Samsung SCX-4655"]),
]


def collect_splix_aliases():
    d = OUT / "arm64-v8a" / "splix-data" / "ppd"
    for base, names in SPLIX_ALIASES:
        text = (d / base).read_bytes().decode("latin-1")
        for name in names:
            slug = re.sub(r"[^A-Za-z0-9]+", "_", name).strip("_")
            t = re.sub(r'^\*(ModelName|ShortNickName|NickName|Product):.*$',
                       lambda m: f'*{m.group(1)}: "{name}"', text, flags=re.M)
            t = re.sub(r'^\*PCFileName:.*$', f'*PCFileName: "{slug[:8].upper()}.PPD"', t, flags=re.M)
            yield "splix", f"alias-{slug}.ppd", t, base


def collect_generic_ppds():
    for engine, base, fname, name, cmd in GENERIC:
        d = OUT / "arm64-v8a" / "escpr-data" / "ppd" if engine == "escpr" else OUT / "hplip-ppd"
        text = (d / base).read_bytes().decode("latin-1")
        if engine == "hplip":
            text = text.replace("@VERSION@", ENGINES["hplip"][0])
        mdl = name.removeprefix("Generic ")
        text = re.sub(r'^\*(ModelName|ShortNickName|Product):.*$', lambda m: f'*{m.group(1)}: "{name}"', text, flags=re.M)
        text = re.sub(r'^\*NickName:.*$', f'*NickName: "{name}"', text, flags=re.M)
        text = re.sub(r'^\*PCFileName:.*$', f'*PCFileName: "{fname[:8].upper()}.PPD"', text, flags=re.M)
        text = re.sub(r'^\*Manufacturer:.*$', '*Manufacturer: "Generic"', text, flags=re.M)
        text = re.sub(r'^\*1284DeviceID:.*$', f'*1284DeviceID: "MFG:Generic;MDL:{mdl};CMD:{",".join(cmd)};"', text, flags=re.M)
        yield engine, fname, text
    yield "builtin-ps", "Generic-PostScript_Level_2.ppd", GENERIC_PS


def make_packs(base_url_packs):
    packs = {}
    (DIST / "packs").mkdir(parents=True, exist_ok=True)
    for engine, (ver, env) in ENGINES.items():
        packs[engine] = {"version": ver, "env": env, "abis": {}}
        for abi in ABIS:
            lib = OUT / abi / f"libfpo_{engine}.so"
            if not lib.exists():
                continue
            name = f"{engine}-{ver}-{abi}.zip"
            driver_json = json.dumps({"engine": engine, "version": ver, "library": lib.name,
                                      "entry": "fpo_entry", "env": env}, indent=1)
            buf = io.BytesIO()
            with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
                z.writestr(zipfile.ZipInfo("driver.json", (1980, 1, 1, 0, 0, 0)), driver_json)
                z.writestr(zipfile.ZipInfo(lib.name, (1980, 1, 1, 0, 0, 0)), lib.read_bytes(), zipfile.ZIP_DEFLATED)
                if engine == "gutenprint":
                    xml = OUT / abi / "gutenprint-data" / "xml"
                    for p in sorted(xml.rglob("*")):
                        if p.is_file():
                            z.writestr(zipfile.ZipInfo(f"data/xml/{p.relative_to(xml).as_posix()}", (1980, 1, 1, 0, 0, 0)),
                                       p.read_bytes(), zipfile.ZIP_DEFLATED)
            data = buf.getvalue()
            (DIST / "packs" / name).write_bytes(data)
            packs[engine]["abis"][abi] = {"file": f"packs/{name}", "sha256": sha256(data), "size": len(data)}
    return packs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="")
    args = ap.parse_args()

    if DIST.exists():
        shutil.rmtree(DIST)
    DIST.mkdir()
    packs = make_packs(args.base_url)

    printers = []
    items = [(e, n, t, None) for e, n, t in list(collect_ppds()) + list(collect_generic_ppds())]
    items += list(collect_splix_aliases())
    for engine, name, text, alias_of in items:
        # CLP/CLX queues name pstoqpdl (PostScript in, needs Ghostscript); our pack takes CUPS raster directly.
        if engine == "splix" and "pstoqpdl" in text:
            text = text.replace("application/vnd.cups-postscript 0 pstoqpdl", "application/vnd.cups-raster 0 rastertoqpdl")
            alias_of = alias_of or "ps-queue-rewritten"
        m = ppd_meta(text)
        if m["language"] and m["language"] != "English":
            continue
        blob = gz(text.encode("latin-1"))
        rel = f"ppd/{engine}/{name}.gz"
        p = DIST / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(blob)
        printers.append({"engine": engine, "file": rel, "sha256": sha256(blob), "size": len(blob),
                         "manufacturer": m["manufacturer"], "model": m["model"],
                         "mfg": m["mfg"], "mdl": m["mdl"], "cmd": m["cmd"],
                         **({"generic": True} if m["manufacturer"] == "Generic" else {}),
                         **({"experimental": alias_of} if alias_of else {})})

    cat = {"version": 1, "base_url": args.base_url, "engines": packs, "printers": printers}
    (DIST / "catalogue.json").write_text(json.dumps(cat, separators=(",", ":")), encoding="utf-8")
    by = {}
    for p in printers:
        by[p["engine"]] = by.get(p["engine"], 0) + 1
    print("printers:", by, "total", len(printers))
    print("packs:", {e: {a: v["size"] for a, v in d["abis"].items()} for e, d in packs.items()})
    total = sum(f.stat().st_size for f in DIST.rglob("*") if f.is_file())
    print("dist size MB:", round(total / 1e6, 1))


if __name__ == "__main__":
    main()

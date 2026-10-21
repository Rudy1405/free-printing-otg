"""Dump the model list from NokoPrint 5.28.4's driver tables (core/b.java of a jadx decompile).

Usage: python -I extract_target_models.py <path to core/b.java> <out.tsv>
Output is an internal coverage target (engine, model, ppd/device-model). It is not shipped in the app.
"""
import re
import sys

TABLES = {"f45444a": "escpr", "f45445b": "gutenprint", "c": "hplip", "f45446d": "splix"}

src = open(sys.argv[1], encoding="utf8").read()
out = []
for var, engine in TABLES.items():
    body = re.search(r"String\[\] " + var + r" = \{(.*?)\};", src, re.S).group(1)
    rows = re.findall(r'"([^"]*)"', body)
    # rows[0] = driver header, rows[1] = "", model rows follow until the next "" separator
    end = rows.index("", 2)
    for r in rows[2:end]:
        f = r.split("|")
        model = f[0].replace(";**Printer", "")
        extra = f[-1] if len(f) > 5 else ""
        out.append((engine, model, extra))
with open(sys.argv[2], "w", encoding="utf8") as fh:
    fh.write("engine\tmodel\tppd_or_devicemodel\n")
    for e in out:
        fh.write("\t".join(e) + "\n")
from collections import Counter
print(Counter(e[0] for e in out), len(out))

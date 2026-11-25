import lief
from pathlib import Path

dest = Path(r"C:\Users\Xie\Documents\Agora\app\libnode\bin\arm64-v8a")
names = [
    "libffmpeg.so",
    "libffprobe.so",
    "libavcodec.so",
    "libavdevice.so",
    "libavfilter.so",
    "libavformat.so",
    "libavutil.so",
    "libswresample.so",
    "libswscale.so",
    "librg.so",
    "libpcre2-8.so",
]
for name in names:
    path = dest / name
    if not path.exists():
        print("missing", name)
        continue
    binary = lief.parse(str(path))
    if binary is None:
        print("parse fail", name)
        continue
    # Drop existing RPATH/RUNPATH then add $ORIGIN
    for entry in list(binary.dynamic_entries):
        if entry.tag in (lief.ELF.DynamicEntry.TAG.RPATH, lief.ELF.DynamicEntry.TAG.RUNPATH):
            binary.remove(entry)
    binary.add(lief.ELF.DynamicEntryRunPath("$ORIGIN"))
    binary.write(str(path))
    again = lief.parse(str(path))
    runpaths = [
        e.runpath if hasattr(e, "runpath") else getattr(e, "name", None)
        for e in again.dynamic_entries
        if e.tag == lief.ELF.DynamicEntry.TAG.RUNPATH
    ]
    print(f"{name}: RUNPATH={runpaths}")

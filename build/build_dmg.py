#!/usr/bin/env python3
import os
import shutil
import subprocess

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VOLNAME = "今天挣了多少钱"
APP_NAME = "今天挣了多少钱.app"
MOUNT = os.path.join(PROJECT, "build", "dmg-mount")
RW_DMG = os.path.join(PROJECT, "build", "earnings-rw.dmg")
FINAL_DMG = os.path.join(PROJECT, "今天挣了多少钱.dmg")


def run(*args, **kwargs):
    print("+", " ".join(str(a) for a in args))
    return subprocess.run(list(args), check=True, **kwargs)


# 1. blank writable image
if os.path.exists(MOUNT):
    run("hdiutil", "detach", MOUNT, "-force", "-quiet")
rw_dmg_old = RW_DMG
if os.path.exists(rw_dmg_old):
    os.remove(rw_dmg_old)
run("hdiutil", "create", "-size", "32m", "-fs", "HFS+", "-volname", VOLNAME,
    "-ov", RW_DMG)

# 2. attach
run("hdiutil", "attach", RW_DMG, "-mountpoint", MOUNT, "-nobrowse",
    "-owners", "off")

try:
    # 3. copy files (ditto preserves code signature)
    os.makedirs(os.path.join(MOUNT, ".background"), exist_ok=True)
    shutil.copyfile(os.path.join(PROJECT, "build", "dmg_bg.png"),
                    os.path.join(MOUNT, ".background", "background.png"))
    run("ditto", os.path.join(PROJECT, APP_NAME), os.path.join(MOUNT, APP_NAME))
    os.symlink("/Applications", os.path.join(MOUNT, "Applications"))

    # 4. .DS_Store
    from ds_store import DSStore
    from mac_alias import Alias

    alias = Alias.for_file(os.path.join(MOUNT, ".background", "background.png"))

    bwsp = {
        "ShowStatusBar": False,
        "WindowBounds": "{{200, 120}, {660, 428}}",
        "ContainerShowSidebar": False,
        "PreviewPaneVisibility": False,
        "SidebarWidth": 0,
        "ShowTabView": False,
        "ShowToolbar": False,
        "ShowPathbar": False,
        "ShowSidebar": False,
    }

    icvp = {
        "viewOptionsVersion": 1,
        "backgroundType": 2,
        "backgroundImageAlias": alias.to_bytes(),
        "gridOffsetX": 0.0,
        "gridOffsetY": 0.0,
        "gridSpacing": 100.0,
        "arrangeBy": "none",
        "showIconPreview": True,
        "showItemInfo": False,
        "labelOnBottom": True,
        "textSize": 14.0,
        "iconSize": 96.0,
        "scrollPositionX": 0.0,
        "scrollPositionY": 0.0,
    }

    with DSStore.open(os.path.join(MOUNT, ".DS_Store"), "w+") as d:
        d["."]["vSrn"] = ("long", 1)
        d["."]["bwsp"] = bwsp
        d["."]["icvp"] = icvp
        d["."]["icvl"] = (b"type", b"icnv")
        d[APP_NAME]["Iloc"] = (140, 200)
        d["Applications"]["Iloc"] = (520, 200)

    shutil.rmtree(os.path.join(MOUNT, ".Trashes"), True)
finally:
    # 5. detach
    run("hdiutil", "detach", MOUNT, "-quiet")

# 6. convert to compressed read-only
if os.path.exists(FINAL_DMG):
    os.remove(FINAL_DMG)
run("hdiutil", "convert", RW_DMG, "-format", "UDBZ", "-o", FINAL_DMG)
os.remove(RW_DMG)

print("\nDONE:", FINAL_DMG)
print("size:", round(os.path.getsize(FINAL_DMG) / 1024 / 1024, 2), "MB")

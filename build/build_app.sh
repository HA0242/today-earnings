#!/bin/bash
# 在你的 Mac 上（不是远程环境！）用 Xcode 命令行工具编译 App 并打包 DMG。
# 用法：在 Terminal 里 cd 到项目根目录，然后运行：
#   bash build/build_app.sh
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

APP_NAME="今天挣了多少钱.app"
EXECUTABLE_NAME="TodayEarnings"
WORK_DIR="$PROJECT_DIR/build/.work"
ICONSET_DIR="$PROJECT_DIR/assets/AppIcon.iconset"
ICNS_PATH="$PROJECT_DIR/assets/AppIcon.icns"
SOURCE_ICON="$PROJECT_DIR/assets/icon_1024.png"

echo "==> 清理旧产物"
rm -rf "$WORK_DIR" "$PROJECT_DIR/$APP_NAME" "$PROJECT_DIR/今天挣了多少钱.dmg"
mkdir -p "$WORK_DIR"

echo "==> 从 assets/icon_1024.png 重新生成图标（iconset + icns）"
rm -rf "$ICONSET_DIR"
mkdir -p "$ICONSET_DIR"
sips -z 16 16     "$SOURCE_ICON" --out "$ICONSET_DIR/icon_16x16.png"      >/dev/null
sips -z 32 32     "$SOURCE_ICON" --out "$ICONSET_DIR/icon_16x16@2x.png"  >/dev/null
sips -z 32 32     "$SOURCE_ICON" --out "$ICONSET_DIR/icon_32x32.png"     >/dev/null
sips -z 64 64     "$SOURCE_ICON" --out "$ICONSET_DIR/icon_32x32@2x.png"  >/dev/null
sips -z 128 128   "$SOURCE_ICON" --out "$ICONSET_DIR/icon_128x128.png"   >/dev/null
sips -z 256 256   "$SOURCE_ICON" --out "$ICONSET_DIR/icon_128x128@2x.png" >/dev/null
sips -z 256 256   "$SOURCE_ICON" --out "$ICONSET_DIR/icon_256x256.png"   >/dev/null
sips -z 512 512   "$SOURCE_ICON" --out "$ICONSET_DIR/icon_256x256@2x.png" >/dev/null
sips -z 512 512   "$SOURCE_ICON" --out "$ICONSET_DIR/icon_512x512.png"   >/dev/null
cp "$SOURCE_ICON" "$ICONSET_DIR/icon_512x512@2x.png"
iconutil -c icns "$ICONSET_DIR" -o "$ICNS_PATH"

echo "==> 编译 Swift 源码"
xcrun swiftc \
  -O \
  -o "$WORK_DIR/$EXECUTABLE_NAME" \
  src/Shared.swift src/TodayEarningsApp.swift src/CalendarPage.swift src/StatsPage.swift

echo "==> 组装 App Bundle"
APP_DIR="$PROJECT_DIR/$APP_NAME"
mkdir -p "$APP_DIR/Contents/MacOS" "$APP_DIR/Contents/Resources"
cp "$WORK_DIR/$EXECUTABLE_NAME" "$APP_DIR/Contents/MacOS/$EXECUTABLE_NAME"
cp "$PROJECT_DIR/build/Info.plist" "$APP_DIR/Contents/Info.plist"
cp "$ICNS_PATH" "$APP_DIR/Contents/Resources/AppIcon.icns"

echo "==> 本地签名（ad-hoc，仅供自己使用）"
codesign --force --deep --sign - "$APP_DIR"

echo "==> 安装打包所需的 Python 依赖（ds_store / mac_alias）"
python3 -m pip show ds_store >/dev/null 2>&1 || python3 -m pip install --user ds_store mac_alias

echo "==> 生成 DMG"
python3 "$PROJECT_DIR/build/build_dmg.py"

echo ""
echo "完成：$PROJECT_DIR/今天挣了多少钱.dmg"

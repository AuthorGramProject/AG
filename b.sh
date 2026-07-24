#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="${1:-$HOME/authorgram-build}"
RES_DIR="$REPO_DIR/TMessagesProj/src/main/res"
VECTOR_FILE="$RES_DIR/drawable/ic_launcher_toss_dark_blue_foreground.xml"

if [ ! -f "$VECTOR_FILE" ]; then
  echo "ПОМИЛКА: не знайдено $VECTOR_FILE"
  exit 1
fi

echo "==> Заміна VectorDrawable на bitmap-посилання (старий NagramX логотип -> наш PNG)"
cat > "$VECTOR_FILE" <<'XML_EOF'
<?xml version="1.0" encoding="utf-8"?>
<bitmap xmlns:android="http://schemas.android.com/apk/res/android"
    android:src="@mipmap/ic_launcher_foreground"
    android:gravity="center" />
XML_EOF

echo "    Записано: $VECTOR_FILE"
echo ""
echo "==> Перевірка результату:"
cat "$VECTOR_FILE"

echo ""
echo "==> Нагадування: переконайся, що @mipmap/ic_launcher_foreground присутній"
echo "    у всіх щільностях (mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi) - він уже мав бути"
echo "    замінений попереднім скриптом 07_install_icon.sh."
find "$RES_DIR" -path "*mipmap-*" -iname "ic_launcher_foreground.png"

echo ""
echo "==> git diff для цього файлу:"
cd "$REPO_DIR"
git diff TMessagesProj/src/main/res/drawable/ic_launcher_toss_dark_blue_foreground.xml

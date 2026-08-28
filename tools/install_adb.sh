#!/usr/bin/env bash
# 一鍵安裝 APK 到 MIUI/HyperOS 裝置（自動點掉「USB 安裝提醒」確認框）。
#
# 為什麼需要這支腳本：MIUI 的 `adb install` 會跳出「USB 安裝提醒」對話框，
# 其中「拒絕(N)」按鈕帶 N 秒倒數，時間到自動拒絕 → 回傳
#   INSTALL_FAILED_USER_RESTRICTED: Install canceled by user
# 因此必須在倒數內自動點「繼續安裝」，單獨跑 `adb install` 幾乎必失敗。
#
# 用法：bash tools/install_adb.sh <serial> <apk路徑>
set -u

SERIAL="${1:?用法: install_adb.sh <serial> <apk>}"
APK="${2:?用法: install_adb.sh <serial> <apk>}"

# 背景啟動 install（它會卡在等待對話框確認）
adb -s "$SERIAL" install -r "$APK" > /tmp/adb_install.log 2>&1 &
IPID=$!

# 對話框約 1 秒後出現、約 7 秒自動拒絕；在視窗內輪詢並點「繼續安裝」
BTN_RE='text="(繼續安裝|继续安装|Continue|Install|OK|繼續)"'

for _ in $(seq 1 12); do
  sleep 1
  XML=$(adb -s "$SERIAL" shell 'uiautomator dump /sdcard/inst.xml >/dev/null 2>&1; cat /sdcard/inst.xml' 2>/dev/null)
  # 只取 bounds= 屬性，再從中抓四個數字——絕不從整行抓，否則會把
  # resource-id="android:id/button2" 的「2」也當成座標（本專案實測踩過的坑）
  BOUNDS=$(printf '%s' "$XML" \
    | grep -oE "$BTN_RE[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" \
    | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
  [ -z "$BOUNDS" ] && continue

  NUMS=$(printf '%s' "$BOUNDS" | grep -oE '[0-9]+')
  X1=$(printf '%s' "$NUMS" | sed -n 1p)
  Y1=$(printf '%s' "$NUMS" | sed -n 2p)
  X2=$(printf '%s' "$NUMS" | sed -n 3p)
  Y2=$(printf '%s' "$NUMS" | sed -n 4p)
  CX=$(( (X1 + X2) / 2 ))
  CY=$(( (Y1 + Y2) / 2 ))
  adb -s "$SERIAL" shell input tap "$CX" "$CY"
  echo "已點「繼續安裝」@ ($CX,$CY)"
  break
done

wait "$IPID"
cat /tmp/adb_install.log

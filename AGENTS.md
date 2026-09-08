# 專案記憶（跨 session 持久決策）

此檔記錄使用者已明確拍板的決策，避免重複提問或重複提案。未來工作前先讀這裡。

## 已否決的方向（不要再次提案）

- **Per-source IP 連線數 / 握手速率限制（濫用防護）**：不做。理由：本代理只在信任的本地區域網路（LAN）使用，非公開暴露，無需此類限流。已多次否決，請勿再提出。

## 開發環境

- **Linux 測試環境**：本機有 VMware 安裝的 Debian VM，可執行依賴 `<sys/epoll.h>` 的 `epoll_integration_test`（host C 測試在 Windows/MinGW 上會跳過這條）。
  - SSH：`ssh tokyoxpa3@192.168.17.134`，密碼 `steroids`（本機實驗用 VM）。
  - 需要跑 Linux-only 的 native 整合測試時，直接連到這台，不要再把「開發機是 Windows 無法本地跑 epoll 測試」當作缺口重複提出。

## 既定的效能/設計共識

- 流量位元組統計（tx/rx bytes）：採用 **per-worker 累加、讀取 stats 時才加總** 的寫法，避免跨執行緒 cache line 競爭。此項開銷可忽略，不視為性能風險。

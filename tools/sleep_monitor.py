#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sleep_monitor.py —— 睡眠监测常驻守护（miwear sleep --daemon 的内核）

做什么：
  · 轮询手表推来的健身记录（App 落在 files/fitness/<id>.bin）
  · 按 AllDaySleep 的 isSleepFinish 做 awake ↔ asleep 状态机
  · 入睡 / 起床 时：写一条日志（含「入睡时间」和「检测/记录时间」）并弹本机通知

用法：
  sleep_monitor.py [--interval 60] [--once] [--test-notify] [--state DIR]
"""
import json
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fitness_decode as fd  # noqa: E402

PKG = "com.miwear.ctl"
FIT_DIR = "/data/data/%s/files/fitness" % PKG
MIWEAR = os.environ.get("MIWEAR_BIN") or os.path.join(HERE, "miwear")


def run(cmd, binary=False, timeout=60):
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                           timeout=timeout)
        return p.stdout if binary else p.stdout.decode("utf-8", "replace")
    except Exception:
        return b"" if binary else ""


def tstr(ts):
    return time.strftime("%m-%d %H:%M:%S", time.localtime(ts)) if ts else "-"


def pushed_ids():
    """App 落盘的记录 id（从文件名）"""
    out = run(["su", "-c", "ls -1 %s/*.bin 2>/dev/null" % FIT_DIR])
    return [os.path.basename(l.strip())[:-4] for l in out.splitlines() if l.strip().endswith(".bin")]


def id_info(hexid):
    try:
        b = bytes.fromhex(hexid)
    except Exception:
        return None
    if len(b) < 7:
        return None
    ts = int.from_bytes(b[:4], "little")
    ver, t = b[5], b[6]
    dt = (t >> 7) & 1
    daily = 0 if dt else (t & 0x7F) >> 2
    return {"ts": ts, "ver": ver, "daily": daily, "file": t & 3, "id": hexid}


def newest_sleep_id(ids=None):
    """最新的睡眠段：dt8/ft1 > dt3 > dt2"""
    best = None
    for h in (ids if ids is not None else pushed_ids()):
        i = id_info(h)
        if not i:
            continue
        sc = 3 if (i["daily"] == 8 and i["file"] == 1) else (2 if i["daily"] in (2, 3) else 0)
        if sc and (best is None or (sc, i["ts"]) > (best[0], best[1])):
            best = (sc, i["ts"], h)
    return best[2] if best else None


def read_record(hexid):
    raw = run(["su", "-c", "cat %s/%s.bin" % (FIT_DIR, hexid)], binary=True)
    if not raw or len(raw) < 9:
        return None
    try:
        return fd.decode_bytes(raw)
    except Exception:
        return None


def miwear(*args, timeout=90):
    return run([MIWEAR] + list(args), timeout=timeout)


def notify(title, text, nid=7788):
    """弹本机通知（App 自己发，有横幅+声音）"""
    js = json.dumps({"cmd": "alert", "title": title, "text": text, "id": nid}, ensure_ascii=False)
    out = run(["python3", os.path.join(HERE, "miwear_rpc.py"), js, "--timeout", "15"], timeout=30)
    return out


def load_state(path):
    try:
        return json.load(open(path))
    except Exception:
        return {}


def save_state(path, st):
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        json.dump(st, open(tmp, "w"), ensure_ascii=False)
        os.replace(tmp, path)
    except Exception:
        pass


def logline(logpath, msg):
    line = "[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg)
    try:
        os.makedirs(os.path.dirname(logpath), exist_ok=True)
        with open(logpath, "a") as f:
            f.write(line + "\n")
    except Exception:
        pass
    print(line, flush=True)


def sleep_records(limit=4):
    """最近的几条睡眠段（已解析）：[(ts, id, res), ...] 按时间倒序"""
    ids = pushed_ids()
    cand = []
    for h in ids:
        i = id_info(h)
        if not i:
            continue
        sc = 3 if (i["daily"] == 8 and i["file"] == 1) else (2 if i["daily"] in (2, 3) else 0)
        if sc:
            cand.append((sc, i["ts"], h))
    cand.sort(reverse=True)
    out = []
    for _sc, ts, h in cand[:limit]:
        r = read_record(h)
        if r and r.get("sleep"):
            out.append((ts, h, r))
    return out


def main():
    ap = sys.argv[1:]
    interval = 60
    once = "--once" in ap
    test = "--test-notify" in ap
    base = os.environ.get("MIWEAR_SLEEP_DIR") or os.path.expanduser("~/.miwear-sleep")
    if "--interval" in ap:
        interval = int(ap[ap.index("--interval") + 1])
    if "--state" in ap:
        base = ap[ap.index("--state") + 1]
    state_path = os.path.join(base, "state.json")
    log_path = os.path.join(base, "sleep.log")

    if test:
        notify("😴 睡眠监测测试", "如果你看到这条通知，说明提醒通道通了。\n时间 " + tstr(time.time()))
        print("已发测试通知")
        return 0

    if not shutil.which("su"):
        logline(log_path, "⚠ 找不到 su，无法读 App 私有目录")

    st = load_state(state_path)
    st.setdefault("state", "awake")          # awake | asleep
    st.setdefault("lastId", None)
    logline(log_path, "监测启动（interval=%ss，state=%s）" % (interval, st["state"]))

    miss = 0
    while True:
        try:
            recs = sleep_records()
            if recs:
                # 以「最新那一段」为准：未结束=在睡；已结束=醒着
                ts, rid, rec = recs[0]
                s = rec["sleep"]
                fin = s.get("isSleepFinish")
                if (not fin) and st["state"] != "asleep":
                    now = time.time()
                    st.update(state="asleep", lastId=rid, bedTime=s.get("bedTime"), detectedAt=now)
                    save_state(state_path, st)
                    msg = ("入睡时间 %s（表记录）\n检测到 %s\n记录 id %s"
                           % (tstr(s.get("bedTime")), tstr(now), rid))
                    logline(log_path, "😴 入睡  bedTime=%s  detected=%s  id=%s"
                            % (tstr(s.get("bedTime")), tstr(now), rid))
                    notify("😴 已入睡", msg)
                elif fin and st["state"] == "asleep":
                    now = time.time()
                    bed = st.get("bedTime") or s.get("bedTime") or 0
                    dur = (s.get("wakeupTime", 0) - bed) / 3600.0 if s.get("wakeupTime") else 0
                    st.update(state="awake", lastId=rid, wakeupTime=s.get("wakeupTime"),
                              detectedAt=now)
                    save_state(state_path, st)
                    msg = ("起床时间 %s\n睡了 %.1f 小时\n检测到 %s\n记录 id %s"
                           % (tstr(s.get("wakeupTime")), dur, tstr(now), rid))
                    logline(log_path, "☀️ 起床  wakeup=%s  slept=%.1fh  detected=%s  id=%s"
                            % (tstr(s.get("wakeupTime")), dur, tstr(now), rid))
                    notify("☀️ 已起床", msg)
                elif st.get("lastId") != rid:
                    st["lastId"] = rid
                    save_state(state_path, st)
                    logline(log_path, "· 新睡眠段 id=%s  isSleepFinish=%s  bed=%s wake=%s"
                            % (rid, fin, tstr(s.get("bedTime")), tstr(s.get("wakeupTime"))))
                miss = 0
            else:
                miss += 1
                if miss % 5 == 1:
                    miwear("fitness", "ids", timeout=60)
        except Exception as e:
            logline(log_path, "⚠ 循环异常: %r" % e)
        if once:
            return 0
        time.sleep(interval)


if __name__ == "__main__":
    sys.exit(main())

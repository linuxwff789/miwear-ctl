#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fitness_decode.py —— 解析 miwear 从手表拉下来的健身记录（module 8 / ch5）

文件布局（由 App 落盘的就是手表原样）：<7 字节 device data id> 0x00 <dataValid> <body>
  id       = [0:4] 时间戳 u32 LE | [4] 时区(15min,+8:00=0x20) | [5] 版本 | [6] 类型
  type     = (dataType<<7) | (sportType<<2) | (dailyType<<2) | fileType
  dataValid = 有效性位图，长度按类型/版本固定（见 VALID_LEN）
  body     = 各字段（native 布局，见下）

已实现：
  · DailyRecord (dailyType 0 / fileType 0) —— 每分钟一条：活动类型/步数/心率/血氧/压力/卡路里
  · AllDaySleep (dailyType 8 / fileType 1) —— isSleepFinish / 上床 / 起床 / 心率串

用法:  fitness_decode.py <file.bin> [--json] [--last N]
"""
import datetime
import json
import struct
import sys

DAILY = {
    0: {0: "DailyRecord", 1: "DailyReport"},
    2: "DaytimeSleep", 3: "NightSleep", 5: "UserProfile",
    6: "ManualMeasure", 8: "AllDaySleep", 9: "AbnormalRecord",
    10: "WeightRecord", 11: "EcgMeasure", 12: "TemperatureMeasure",
}

# FitnessDataValidity.getAllDaySleepValidityLen(version)
ALLDAY_SLEEP_VALID_LEN = {1: 1, 2: 1, 3: 1, 4: 1, 5: 2, 6: 3}
# getDailyRecordValidityLen
def daily_record_valid_len(ver):
    if ver in (1, 2):
        return 4
    if ver == 3:
        return 5
    if ver == 4:
        return 6
    if ver == 5:
        return 7
    return -1

ACTIVITY_TYPE = {0: "未知", 1: "静止", 2: "走", 3: "跑"}
ACTIVITY_STRENGTH = {0: "静止", 1: "微弱", 2: "低强度", 3: "中等", 4: "高强度", 5: "剧烈", 7: "未知"}


def bits(byte_arr, high_bit_first):
    """按 byteSize 拼出的整数（bytes 已知 -1 已补 0xff）"""
    v = 0
    n = len(byte_arr)
    for i, x in enumerate(byte_arr):
        v |= (x & 0xFF) << ((n - 1 - i) * 8 if high_bit_first else i * 8)
    return v


def getbit(val, hi, lo):
    return (val >> lo) & ((1 << (hi - lo + 1)) - 1)


def decode_id(b):
    ts, = struct.unpack("<I", b[:4])
    tz, ver, t = b[4], b[5], b[6]
    data_type = (t >> 7) & 1
    mid = (t & 0x7F) >> 2
    return {
        "ts": ts, "tz": tz, "version": ver, "data_type": data_type,
        "sport_type": mid if data_type else 0,
        "daily_type": 0 if data_type else mid,
        "file_type": t & 3, "type_byte": t,
    }


def id_name(i):
    if i["data_type"]:
        return "Sport(%d)" % i["sport_type"]
    d = DAILY.get(i["daily_type"])
    if isinstance(d, dict):
        return d.get(i["file_type"], "daily%d/file%d" % (i["daily_type"], i["file_type"]))
    return d or ("daily%d/file%d" % (i["daily_type"], i["file_type"]))


def valid_len(i):
    if i["data_type"] == 0 and i["daily_type"] == 0 and i["file_type"] == 0:
        return daily_record_valid_len(i["version"])
    if i["data_type"] == 0 and i["daily_type"] == 8 and i["file_type"] == 1:
        return ALLDAY_SLEEP_VALID_LEN.get(i["version"], -1)
    return -1


def parse_daily_record(res, data_valid, body):
    """daily_activity_record_v3（schema / native 同布局）"""
    v = bits(data_valid, True)
    B = {
        "heartRateAndStep":       (39, 2),
        "activeTypeAndCalories":  (35, 1),
        "activeStrengthAndSportType": (31, 1),
        "newDistance":            (27, 2),
        "heartRate":              (23, 1),
        "dumpEnergy":             (19, 1),
        "caloriesAndEnergy":      (15, 2),
        "bloodOxygen":            (11, 1),
        "curPressure":            (7, 1),
    }
    present = {k: (getbit(v, hi, hi) == 1) for k, (hi, _) in B.items()}
    size = sum(sz for k, (_, sz) in B.items() if present[k])
    if size == 0:
        return
    items = []
    off = 0
    while off + size <= len(body):
        p = off
        it = {}
        hr_rise = 0
        if present["heartRateAndStep"]:
            val = body[p] | (body[p + 1] << 8); p += 2
            hr_rise = getbit(val, 14, 14)
            it["newSteps"] = getbit(val, 13, 0)
            it["hrRise"] = bool(hr_rise)
        if present["activeTypeAndCalories"]:
            val = body[p]; p += 1
            it["activityType"] = ACTIVITY_TYPE.get(getbit(val, 7, 6), getbit(val, 7, 6))
            it["activityCalories"] = getbit(val, 5, 0)
        if present["activeStrengthAndSportType"]:
            val = body[p]; p += 1
            it["activeStrength"] = ACTIVITY_STRENGTH.get(getbit(val, 7, 5), getbit(val, 7, 5))
            it["sportType"] = getbit(val, 4, 0)
        if present["newDistance"]:
            it["newDistance"] = body[p] | (body[p + 1] << 8); p += 2
        if present["heartRate"]:
            it["hr"] = body[p]; p += 1
        if present["dumpEnergy"]:
            it["dumpEnergy"] = body[p]; p += 1
        if present["caloriesAndEnergy"]:
            val = body[p] | (body[p + 1] << 8); p += 2
            it["newCalories"] = getbit(val, 15, 10)
            it["energyStatus"] = getbit(val, 9, 8)
            esv = getbit(val, 7, 0)
            it["energyStatusValue"] = ((1 if (esv & 0x80) else -1) * (esv & 0x7F))
        if present["bloodOxygen"]:
            it["spo2"] = body[p]; p += 1
        if present["curPressure"]:
            it["stress"] = body[p]; p += 1
        if hr_rise:
            it["hrPreAbnormal"] = body[p]; p += 1
        items.append(it)
        off = p
    res["kind"] = "DailyRecord"
    res["validFlags"] = "0x%x" % v
    res["present"] = [k for k in B if present[k]]
    res["itemCount"] = len(items)
    res["items"] = items


def parse_all_day_sleep(res, data_valid, body):
    """AllDaySleep native（AllDaySleepParser，v1~v6）"""
    v = bits(data_valid, True)
    ver = res["id"]["version"]
    # 有效性位：按 dataTypeArray 顺序，type>=0 且 supportVersion<=ver 才占一位
    # 位图 = 大端（byte0 的 bit7 是第 0 位）
    order = [(0, 1), (1, 1), (2, 4), (6, 5), (7, 5), (8, 5), (9, 5), (10, 5), (3, 1), (4, 1), (5, 3), (11, 6)]
    valid = {}
    i = 0
    for t, sv in order:
        if sv <= ver:
            valid[t] = bool((data_valid[i // 8] >> (7 - i % 8)) & 1)
            i += 1
        else:
            valid[t] = False
    p = 0
    r = {}
    r["isSleepFinish"] = body[p] == 1; p += 1
    r["bedTime"] = struct.unpack("<I", body[p:p + 4])[0]; p += 4
    r["wakeupTime"] = struct.unpack("<I", body[p:p + 4])[0]; p += 4
    if ver >= 4:
        r["sleepQuality"] = body[p] if valid.get(2) else None; p += 1
    if ver >= 5:
        r["sleepEfficiency"] = body[p] if valid.get(6) else None; p += 1
        r["entrySleepDuration"] = struct.unpack("<I", body[p:p + 4])[0]; p += 4
        r["linBedDuration"] = struct.unpack("<I", body[p:p + 4])[0]; p += 4
        r["goBedTime"] = struct.unpack("<I", body[p:p + 4])[0]; p += 4
        r["leaveBedTime"] = struct.unpack("<I", body[p:p + 4])[0]; p += 4
    # 打点：hr(1B) / spo2(1B) / snore(4B float)，带 2B 间隔 + 2B 个数 + 4B 首点时间
    series = {}
    for t, name, sz, fmt in ((3, "hrSeries", 1, None), (4, "spo2Series", 1, None), (5, "snoreSeries", 4, "f")):
        if not valid.get(t):
            continue
        if p + 8 > len(body):
            break
        interval = body[p] | (body[p + 1] << 8); p += 2
        count = body[p] | (body[p + 1] << 8); p += 2
        first = struct.unpack("<I", body[p:p + 4])[0]; p += 4
        need = count * sz
        vals = body[p:p + need]
        if fmt == "f":
            vals = list(struct.unpack("<%df" % count, vals[:need])) if len(vals) >= need else []
        p += need
        series[name] = {"interval": interval, "count": count, "firstTime": first, "values": list(vals)}
    res["kind"] = "AllDaySleep"
    res["sleep"] = r
    res["series"] = series


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    path = sys.argv[1]
    data = open(path, "rb").read()
    args = sys.argv[1:]
    as_json = "--json" in args
    last = None
    if "--last" in args:
        last = int(args[args.index("--last") + 1])

    if len(data) < 9:
        print("文件太短: %d 字节" % len(data))
        return 1
    i = decode_id(data[:7])
    res = {"file": path, "len": len(data), "id": i, "idHex": data[:7].hex(),
           "type": id_name(i)}
    n = valid_len(i)
    ds = 7 + 1
    if n > 0 and len(data) >= ds + n:
        data_valid = data[ds:ds + n]
        body = data[ds + n:]
    else:
        data_valid, body = b"", data[ds:]
    res["dataValidLen"] = n
    res["dataValid"] = data_valid.hex()

    try:
        if i["data_type"] == 0 and i["daily_type"] == 0 and i["file_type"] == 0:
            parse_daily_record(res, data_valid, body)
        elif i["data_type"] == 0 and i["daily_type"] == 8 and i["file_type"] == 1:
            parse_all_day_sleep(res, data_valid, body)
    except Exception as e:
        res["parseError"] = repr(e)

    body_left = len(body)

    if as_json:
        for k in ("series",):
            if k in res:
                for s in res[k].values():
                    s["values"] = s["values"][:50] + (["…%d more" % (len(s["values"]) - 50)] if len(s["values"]) > 50 else [])
        if last and "items" in res:
            res["items"] = res["items"][-last:]
        print(json.dumps(res, ensure_ascii=False, indent=1))
        return 0

    def t(x):
        return datetime.datetime.fromtimestamp(x).strftime("%m-%d %H:%M:%S") if x else "-"

    print("文件   : %s（%d 字节，body %d）" % (path, len(data), body_left))
    print("data id: %s" % res["idHex"])
    print("时间   : %s  tz=%d(%+.2fh) ver=%d" % (t(i["ts"]), i["tz"], i["tz"] * 15 / 60.0, i["version"]))
    print("类型   : %s" % res["type"])
    print("有效位 : len=%s  0x%s" % (n, data_valid.hex()))
    if res.get("kind") == "AllDaySleep":
        s = res["sleep"]
        print("── 睡眠 ──")
        print("  本次是否已结束 : %s%s" % (s["isSleepFinish"], "" if s["isSleepFinish"] else "   ← 还在睡！"))
        print("  上床 / 入睡    : %s / %s" % (t(s["bedTime"]), t(s["bedTime"])))
        print("  醒来 / 起床    : %s" % t(s["wakeupTime"]))
        if s.get("sleepQuality") is not None:
            print("  睡眠呼吸质量   : %s" % s["sleepQuality"])
        for name, ser in (res.get("series") or {}).items():
            v = ser["values"]
            print("  %-10s: %d 点，间隔 %ds，首点 %s，均值 %.1f"
                  % (name, ser["count"], ser["interval"], t(ser["firstTime"]),
                     (sum(v) / len(v)) if v else 0))
    elif res.get("kind") == "DailyRecord":
        print("── 分钟级记录（共 %d 条）──" % res["itemCount"])
        print("  存在字段:", ", ".join(res["present"]))
        for k, it in enumerate(res["items"][:5]):
            print("   #%d %s" % (k, it))
        if res["itemCount"] > 5:
            print("   …")
            for k, it in enumerate(res["items"][-3:], res["itemCount"] - 3):
                print("   #%d %s" % (k, it))
    else:
        print("body   : %s" % body[:64].hex(" "))
    return 0


if __name__ == "__main__":
    sys.exit(main())
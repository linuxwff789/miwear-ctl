#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fitness_decode.py —— 解析 miwear 从手表拉下来的健身记录（module 8 / ch5）

文件格式：7 字节 data id + body（CRC32 已被 App 去掉）
  id = [0:4] 时间戳 u32 LE | [4] 时区(15min,+8:00=0x20) | [5] 版本 | [6] 类型
  type = (dataType<<7) | (sportType<<2) | (dailyType<<2) | fileType

用法:  fitness_decode.py <file.bin> [--hex N]
"""
import struct
import sys
import datetime

DAILY = {
    0: {0: "DailyRecord(分钟级活动/睡眠)", 1: "DailyReport(日汇总)"},
    2: "DaytimeSleep(午睡)",
    3: "NightSleep(夜间睡眠)",
    5: "UserProfile",
    6: "ManualMeasure(手动测量)",
    8: "AllDaySleep(全天睡眠)",
    9: "AbnormalRecord",
    10: "WeightRecord",
    11: "EcgMeasure",
    12: "TemperatureMeasure",
}

SLEEP_STATE = {0: "非睡眠", 2: "深睡", 3: "浅睡", 4: "REM", 5: "清醒", 6: "睡眠轨迹"}


def decode_id(b):
    ts, = struct.unpack("<I", b[:4])
    tz, ver, t = b[4], b[5], b[6]
    data_type = (t >> 7) & 1
    mid = (t & 0x7F) >> 2
    sport = mid if data_type else 0
    daily = 0 if data_type else mid
    file_type = t & 3
    return dict(ts=ts, tz=tz, ver=ver, data_type=data_type, sport=sport,
                daily=daily, file=file_type)


def type_name(i):
    if i["data_type"]:
        return "Sport(%d)" % i["sport"]
    d = DAILY.get(i["daily"])
    if isinstance(d, dict):
        return d.get(i["file"], "daily%d/file%d" % (i["daily"], i["file"]))
    return d or ("daily%d/file%d" % (i["daily"], i["file"]))


def hexdump(b, n=64, width=16):
    lines = []
    for o in range(0, min(len(b), n), width):
        chunk = b[o:o + width]
        hx = " ".join("%02x" % x for x in chunk)
        asc = "".join(chr(x) if 32 <= x < 127 else "." for x in chunk)
        lines.append("  %04x  %-*s  %s" % (o, width * 3 - 1, hx, asc))
    if len(b) > n:
        lines.append("  … 共 %d 字节" % len(b))
    return "\n".join(lines)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    path = sys.argv[1]
    data = open(path, "rb").read()
    if len(data) < 7:
        print("文件太短: %d 字节" % len(data))
        return 1
    i = decode_id(data[:7])
    body = data[7:]
    t = datetime.datetime.fromtimestamp(i["ts"])
    print("文件   : %s（%d 字节）" % (path, len(data)))
    print("data id: %s" % data[:7].hex())
    print("时间   : %s   tz=%d(%+.2fh)  ver=%d" % (t, i["tz"], (i["tz"] * 15) / 60.0, i["ver"]))
    print("类型   : %s   dataType=%d dailyType=%d fileType=%d"
          % (type_name(i), i["data_type"], i["daily"], i["file"]))
    print("body   : %d 字节" % len(body))
    print("--- 头部 ---")
    print(hexdump(body, 96))
    return 0


if __name__ == "__main__":
    sys.exit(main())

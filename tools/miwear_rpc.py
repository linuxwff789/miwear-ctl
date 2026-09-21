#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
miwear RPC 客户端 —— 把一条命令发给手机上的 miwear 常驻服务（CmdServer）。

用法:
  miwear_rpc.py '<json 命令>' [--port N] [--timeout S] [--raw] [--follow]

输出:
  服务端日志实时打到 stdout；命令结束返回 0/1。
"""
import json
import os
import re
import socket
import sys
import time

FRAME_RE = re.compile(r'^(→|←) (DATA|CMD) seq=\d+ ch=[\d-]+ op=[\d-]+ len=\d+ .{60,}$')


def parse_extras(args):
    """把 am 风格的 extra 解析成 dict。
    am 的写法是三段式：--es key val / --ez key true / --ei key 5
    （也兼容 --key val / --key）
    """
    kv = {}
    i = 0
    types = ("es", "ez", "ei", "el", "ef", "ed", "esn", "eia", "ela", "esa")
    while i < len(args):
        a = args[i]
        if a.startswith("--") and a[2:] in types:
            if i + 1 < len(args):
                key = args[i + 1]
                val = args[i + 2] if i + 2 < len(args) else "true"
                kv[key] = val
                i += 3
                continue
            i += 1
            continue
        if a.startswith("--"):
            key = a[2:]
            val = "true"
            if i + 1 < len(args) and not args[i + 1].startswith("--"):
                val = args[i + 1]
                i += 1
            kv[key] = val
        i += 1
    return kv


def extras_to_json(args):
    """把旧的 Intent extra 映射成 CmdServer 的 JSON 命令。不认识的返回空串。"""
    kv = parse_extras(args)
    def g(k, d=""):
        return kv.get(k, d)
    def n(k, d=1):
        try:
            return int(kv.get(k, d))
        except Exception:
            return d
    if g("netproxy") == "true":
        m = {"cmd": "net", "launch_pkg": g("launch_pkg"), "launch_uri": g("launch_uri")}
    elif "install_rpk" in kv:
        m = {"cmd": "install", "path": g("install_rpk")}
    elif g("list_apps") == "true":
        m = {"cmd": "apps"}
    elif g("query_status") == "true":
        m = {"cmd": "info"}
    elif g("battery_only") == "true":
        m = {"cmd": "battery"}
    elif "probe_mod" in kv:
        m = {"cmd": "probe", "mod": n("probe_mod"), "sub": n("probe_sub")}
    elif g("find_device") == "true":
        m = {"cmd": "find"}
    elif "app_pkg" in kv:
        m = {"cmd": "app", "pkg": g("app_pkg")}
    elif "uninstall_pkg" in kv:
        m = {"cmd": "uninstall", "pkg": g("uninstall_pkg"), "fp": g("uninstall_fp")}
    elif "raw_hex" in kv:
        m = {"cmd": "raw", "hex": g("raw_hex")}
    elif "call_number" in kv:
        m = {"cmd": "call", "number": g("call_number"), "name": g("call_name"), "type": n("call_type")}
    elif "msg_pkg" in kv:
        m = {"cmd": "msg", "pkg": g("msg_pkg"), "text": g("msg_text")}
    elif "sync_pkg" in kv:
        m = {"cmd": "sync", "pkg": g("sync_pkg"), "status": n("sync_status")}
    elif "notify_title" in kv:
        m = {"cmd": "notify", "title": g("notify_title"), "text": g("notify_text"),
             "pkg": g("notify_pkg", "com.termux")}
    elif "launch_pkg" in kv:
        m = {"cmd": "launch", "pkg": g("launch_pkg"), "uri": g("launch_uri")}
    else:
        return ""
    return json.dumps(m, ensure_ascii=False)


def main():
    argv = sys.argv[1:]
    if argv and argv[0] == "--extras":
        print(extras_to_json(argv[1:]))
        return 0
    if argv and argv[0] == "--connect":   # 仅供脚本探测服务是否可达
        try:
            socket.create_connection(("127.0.0.1", int(os.environ.get("MIWEAR_PORT", "38787"))), timeout=3).close()
            return 0
        except Exception:
            return 1
    opts = {"port": int(os.environ.get("MIWEAR_PORT", "38787")), "timeout": 60.0,
            "raw": False, "follow": False, "host": "127.0.0.1"}
    rest = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--port":
            opts["port"] = int(argv[i + 1]); i += 2
        elif a == "--timeout":
            opts["timeout"] = float(argv[i + 1]); i += 2
        elif a == "--raw":
            opts["raw"] = True; i += 1
        elif a == "--follow":
            opts["follow"] = True; i += 1
        elif a == "--host":
            opts["host"] = argv[i + 1]; i += 2
        elif a == "--from-env":
            i += 1
        else:
            rest.append(a); i += 1

    payload = "{}" if not rest else rest[0]
    if "--from-env" in argv:
        payload = os.environ.get("MIWEAR_CMD", "{}")
    try:
        json.loads(payload)
    except Exception as e:
        sys.stderr.write("命令不是合法 JSON: %s\n" % e)
        return 2

    try:
        s = socket.create_connection((opts["host"], opts["port"]), timeout=6)
    except Exception as e:
        sys.stderr.write("连不上 miwear 服务 (%s:%d): %s\n" % (opts["host"], opts["port"], e))
        return 3
    s.settimeout(None)
    s.sendall((payload.rstrip("\n") + "\n").encode("utf-8"))

    buf = b""
    deadline = None if opts["timeout"] <= 0 else time.time() + opts["timeout"]
    rc = 1
    done = False
    while True:
        if deadline and time.time() > deadline:
            sys.stderr.write("超时（%.0fs）\n" % opts["timeout"])
            break
        try:
            s.settimeout(0.5 if deadline else 30)
            chunk = s.recv(65536)
        except socket.timeout:
            continue
        except Exception as e:
            if not done:
                sys.stderr.write("连接中断: %s\n" % e)
            break
        if not chunk:
            break
        buf += chunk
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            line = line.decode("utf-8", "replace").strip()
            if not line:
                continue
            if opts["raw"]:
                print(line, flush=True)
            try:
                ev = json.loads(line)
            except Exception:
                if not opts["raw"]:
                    print(line, flush=True)
                continue
            kind = ev.get("ev")
            if kind == "log":
                if opts["raw"]:
                    continue
                msg = ev.get("msg", "")
                if FRAME_RE.match(msg):
                    msg = FRAME_RE.sub(r"\1 \2 (帧数据略)", msg)
                print(msg, flush=True)
            elif kind == "done":
                if not opts["raw"]:
                    if ev.get("error"):
                        sys.stderr.write(" %s\n" % ev["error"])
                    elif "data" in ev and ev["data"]:
                        print(ev["data"], flush=True)
                    else:
                        extra = {k: v for k, v in ev.items()
                                 if k not in ("ev", "ok", "keep", "bye")}
                        if extra:
                            print(" ".join("%s=%s" % (k, v) for k, v in extra.items()), flush=True)
                rc = 0 if ev.get("ok") else 1
                done = True
                if not opts["follow"]:
                    s.close()
                    return rc
            elif kind in ("hello", "pong"):
                pass
            elif not opts["raw"]:
                print(line, flush=True)
        if done and not opts["follow"]:
            break
    try:
        s.close()
    except Exception:
        pass
    return rc


if __name__ == "__main__":
    sys.exit(main())
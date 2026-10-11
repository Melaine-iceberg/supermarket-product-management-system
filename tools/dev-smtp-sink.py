#!/usr/bin/env python3
"""极简 SMTP 接收端，仅用于本地验证「验证码邮件是否真的发得出去」。

它实现了 SMTP 会话所需的最小命令集（EHLO/MAIL/RCPT/DATA/QUIT），
把收到的完整报文（含 MIME 编码后的主题与正文）写到 -o 指定的文件，
供测试脚本断言主题/正文是否包含验证码。

不属于生产代码，仅测试辅助。
"""
import argparse
import socket
import sys
import threading
import time


def handle(conn, out_path, lock):
    conn.settimeout(20)
    f = conn.makefile("rwb", buffering=0)

    def send(line):
        f.write((line + "\r\n").encode())

    send("220 localhost ESMTP smms-test-sink")
    data_mode = False
    buf = []
    envelope = {"from": None, "rcpt": []}
    while True:
        try:
            raw = f.readline()
        except (socket.timeout, OSError):
            break
        if not raw:
            break
        line = raw.decode("utf-8", "replace").rstrip("\r\n")
        if data_mode:
            if line == ".":
                data_mode = False
                msg = "\r\n".join(buf)
                with lock:
                    with open(out_path, "a", encoding="utf-8") as o:
                        o.write("=== MESSAGE %s ===\n" % time.strftime("%H:%M:%S"))
                        o.write("MAIL FROM: %s\n" % envelope["from"])
                        o.write("RCPT TO: %s\n" % ",".join(envelope["rcpt"]))
                        o.write(msg + "\n")
                buf = []
                envelope = {"from": None, "rcpt": []}
                send("250 OK queued")
            else:
                buf.append(line[1:] if line.startswith("..") else line)
            continue

        upper = line.upper()
        if upper.startswith("EHLO") or upper.startswith("HELO"):
            # 不宣告 AUTH/STARTTLS，配合无凭证配置，走最简路径
            send("250-localhost")
            send("250-8BITMIME")
            send("250 SIZE 10485760")
        elif upper.startswith("MAIL FROM"):
            envelope["from"] = line.split(":", 1)[1].strip()
            send("250 OK")
        elif upper.startswith("RCPT TO"):
            envelope["rcpt"].append(line.split(":", 1)[1].strip())
            send("250 OK")
        elif upper.startswith("DATA"):
            data_mode = True
            send("354 End data with <CR><LF>.<CR><LF>")
        elif upper.startswith("RSET"):
            buf, envelope = [], {"from": None, "rcpt": []}
            send("250 OK")
        elif upper.startswith("NOOP"):
            send("250 OK")
        elif upper.startswith("QUIT"):
            send("221 Bye")
            break
        else:
            send("502 Command not implemented")
    try:
        f.close()
        conn.close()
    except OSError:
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=2525)
    ap.add_argument("-o", "--out", default="/tmp/smtp-sink.log")
    args = ap.parse_args()

    lock = threading.Lock()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", args.port))
    srv.listen(16)
    print("smtp sink listening on %d -> %s" % (args.port, args.out), flush=True)
    while True:
        try:
            conn, _ = srv.accept()
        except KeyboardInterrupt:
            break
        threading.Thread(target=handle, args=(conn, args.out, lock), daemon=True).start()


if __name__ == "__main__":
    sys.exit(main())

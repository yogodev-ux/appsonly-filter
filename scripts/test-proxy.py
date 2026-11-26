#!/usr/bin/env python3
"""TEST ONLY: a tiny HTTP proxy (CONNECT and plain GET) on the CI machine.

The escape test points the phone's proxy setting at it to check that a proxy can't get around
the web filter. Every client connection is written to the log file given as the 2nd argument.
Usage: test-proxy.py PORT LOGFILE
"""
import socket, sys, threading

PORT, LOG = int(sys.argv[1]), sys.argv[2]

def pipe(a, b):
    try:
        while True:
            d = a.recv(65536)
            if not d:
                break
            b.sendall(d)
    except OSError:
        pass
    finally:
        for s in (a, b):
            try: s.shutdown(socket.SHUT_RDWR)
            except OSError: pass

def serve(c, addr):
    try:
        head = b""
        while b"\r\n\r\n" not in head and len(head) < 65536:
            d = c.recv(4096)
            if not d:
                return
            head += d
        line = head.split(b"\r\n", 1)[0].decode("latin-1")
        with open(LOG, "a") as f:
            f.write(f"{addr[0]} {line}\n")
        method, target, _ = line.split(" ", 2)
        if method == "CONNECT":
            host, port = target.rsplit(":", 1)
            up = socket.create_connection((host, int(port)), timeout=10)
            c.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n")
            rest = head.split(b"\r\n\r\n", 1)[1]
            if rest:
                up.sendall(rest)
        else:
            from urllib.parse import urlsplit
            u = urlsplit(target)
            up = socket.create_connection((u.hostname, u.port or 80), timeout=10)
            path = (u.path or "/") + (("?" + u.query) if u.query else "")
            up.sendall(head.replace(target.encode(), path.encode(), 1))
        up.settimeout(None); c.settimeout(None)
        threading.Thread(target=pipe, args=(up, c), daemon=True).start()
        pipe(c, up)
    except Exception as e:
        with open(LOG, "a") as f:
            f.write(f"{addr[0]} error {e}\n")
    finally:
        c.close()

s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("0.0.0.0", PORT))
s.listen(50)
while True:
    c, addr = s.accept()
    c.settimeout(15)
    threading.Thread(target=serve, args=(c, addr), daemon=True).start()

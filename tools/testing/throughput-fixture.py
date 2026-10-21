"""Local-only bulk HTTP fixture; used by ThroughputBenchmarkTest on emulator-5558."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import socket

BLOCK = bytes(range(256)) * 256

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def setup(self):
        super().setup()
        self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    def do_GET(self):
        count = min(max(int(self.path.rsplit('/', 1)[-1]), 1), 64 * 1024 * 1024)
        self.send_response(200)
        self.send_header('Content-Length', str(count))
        self.send_header('Connection', 'close')
        self.end_headers()
        while count:
            n = min(count, len(BLOCK))
            self.wfile.write(BLOCK[:n])
            count -= n
        self.close_connection = True
    def do_POST(self):
        remaining = int(self.headers['Content-Length'])
        total = remaining
        while remaining:
            data = self.rfile.read(min(remaining, 65536))
            if not data: break
            remaining -= len(data)
        result = f'BYTES {total - remaining}\n'.encode()
        self.send_response(200)
        self.send_header('Content-Length', str(len(result)))
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(result)
        self.close_connection = True
    def log_message(self, *_): pass

if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', 18082), Handler).serve_forever()

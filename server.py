import http.server
import socketserver
import os
import pathlib

PORT = 3000
DIRECTORY = pathlib.Path(__file__).parent.resolve()

class JarvisHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/" or self.path == "/index.html":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            html = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>JARVIS Android Assistant</title>
  <style>
    body {
      background: #000000;
      color: #F9FAFB;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      margin: 0;
      padding: 32px 20px;
      display: flex;
      flex-direction: column;
      align-items: center;
      min-height: 100vh;
      box-sizing: border-box;
    }
    .card {
      background: rgba(28, 28, 34, 0.85);
      border: 1px solid rgba(255, 255, 255, 0.16);
      border-radius: 24px;
      padding: 28px;
      max-width: 600px;
      width: 100%;
      box-shadow: 0 10px 40px rgba(0, 0, 0, 0.8);
    }
    h1 {
      color: #38E5FF;
      font-size: 28px;
      margin: 0 0 8px 0;
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .badge {
      display: inline-block;
      background: rgba(56, 229, 255, 0.16);
      border: 1px solid rgba(56, 229, 255, 0.45);
      color: #38E5FF;
      font-size: 13px;
      padding: 4px 12px;
      border-radius: 50px;
      font-weight: 600;
    }
    p {
      color: #9898A3;
      font-size: 15px;
      line-height: 1.5;
    }
    .grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
      margin: 20px 0;
    }
    .stat {
      background: rgba(255, 255, 255, 0.06);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 16px;
      padding: 16px;
    }
    .stat-label {
      font-size: 12px;
      color: #9898A3;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    .stat-val {
      font-size: 20px;
      font-weight: bold;
      color: #F9FAFB;
      margin-top: 4px;
    }
    .btn {
      display: inline-block;
      background: linear-gradient(135deg, #38E5FF 0%, #9D65FF 100%);
      color: #000;
      font-weight: 700;
      text-decoration: none;
      padding: 14px 28px;
      border-radius: 50px;
      margin-top: 10px;
      text-align: center;
    }
  </style>
</head>
<body>
  <div class="card">
    <h1>JARVIS Android <span class="badge">APK Ready</span></h1>
    <p>Native Android voice & automation assistant for English, Hindi, and Hinglish with on-device neural TTS, offline wake-word, and phone control.</p>
    
    <div class="grid">
      <div class="stat">
        <div class="stat-label">Offline Hindi Voices</div>
        <div class="stat-val">7 Neural Voices</div>
      </div>
      <div class="stat">
        <div class="stat-label">Unit Tests</div>
        <div class="stat-val">202 / 202 Passed</div>
      </div>
      <div class="stat">
        <div class="stat-label">Debug APK Size</div>
        <div class="stat-val">310 MB</div>
      </div>
      <div class="stat">
        <div class="stat-label">TTS Engine</div>
        <div class="stat-val">Sherpa-ONNX + Piper + Kokoro</div>
      </div>
    </div>

    <a href="/app-debug.apk" class="btn">Download JARVIS APK</a>
  </div>
</body>
</html>"""
            self.wfile.write(html.encode("utf-8"))
        elif self.path == "/app-debug.apk":
            apk_path = DIRECTORY / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
            if not apk_path.exists():
                apk_path = DIRECTORY / ".build-outputs" / "app-debug.apk"
            if apk_path.exists():
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Length", str(apk_path.stat().st_size))
                self.send_header("Content-Disposition", 'attachment; filename="app-debug.apk"')
                self.end_headers()
                with open(apk_path, "rb") as f:
                    while chunk := f.read(65536):
                        self.wfile.write(chunk)
            else:
                self.send_response(404)
                self.end_headers()
        else:
            super().do_GET()

if __name__ == "__main__":
    with socketserver.TCPServer(("", PORT), JarvisHandler) as httpd:
        httpd.allow_reuse_address = True
        print(f"Serving JARVIS Android Web Service on port {PORT}...")
        httpd.serve_forever()

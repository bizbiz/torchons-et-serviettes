"""Serveur web minimal pour le frontend Torchons & Serviettes.

Lance avec :
    cd web && python server.py
Ou :
    .venv/Scripts/python.exe web/server.py
"""
import http.server
import os
import sys

PORT = 8003
DIRECTORY = os.path.dirname(os.path.abspath(__file__))

class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIRECTORY, **kwargs)

    def end_headers(self):
        # CORS pour appels API
        self.send_header('Access-Control-Allow-Origin', '*')
        super().end_headers()

if __name__ == "__main__":
    with http.server.HTTPServer(("0.0.0.0", PORT), Handler) as httpd:
        print(f"T&S Web sur http://0.0.0.0:{PORT}")
        httpd.serve_forever()

# Docker: backend container

The backend runs in a small image (`docker/Dockerfile.backend`) that installs
only `docker/requirements.backend.txt` — FastAPI, Uvicorn, SQLAlchemy, httpx,
PyYAML and NumPy. `backend/` does not import TensorFlow, librosa or pandas, so
the training stack is not needed in this image.

Training, evaluation and the edge agent are **not** containerised by default;
they are one-shot Makefile workflows on the host (see README Quick start).

## Build and run

```bash
cd forestear
docker compose build
docker compose up -d
docker compose ps
curl -s http://127.0.0.1:8000/health
```

The dashboard is at <http://localhost:8000/>. Stop with
`docker compose down` (add `-v` to remove named volumes, if any).

Set a non-default token before exposing the port:

```bash
FORESTEAR_TOKEN="$(openssl rand -hex 24)" docker compose up -d
```

Telegram credentials are passed through if exported:
`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` (see `docs/telegram_setup.md`).

The dashboard basemap can be overridden without rebuilding the image:
`FORESTEAR_TILE_URL`, `FORESTEAR_TILE_ATTRIBUTION`, `FORESTEAR_TILE_MAX_ZOOM`
(see `docs/map.md`). Leave them unset to use `configs/default.yaml`.

## State

`docker-compose.yml` bind-mounts `./data` and `./reports` so the event database,
clips and reports survive container restarts and stay readable on the host.
The container runs as a non-root user (uid 1000). On Linux, if `./data` is
owned by a different uid, create it writable:

```bash
mkdir -p data reports && sudo chown -R 1000:1000 data reports
```

To keep demo data untouched, point the backend at a scratch database and
mount a scratch directory:

```yaml
services:
  backend:
    environment:
      FORESTEAR_DB: /app/data/backend.db
    volumes:
      - ./scratch/data:/app/data
      - ./scratch/reports:/app/reports
```

`FORESTEAR_DB` is read by `backend/models.py`; it exists so load tests and
experiments never touch `data/backend.db`.

## Measured on this machine (2026-11-16)

- `docker compose build` succeeded with the lightweight requirements.
- Image size: **388 MB** (`forestear-backend:latest`). A TF-based backend image
  would be roughly 5× larger; this one intentionally excludes it.
- `docker compose up -d` → container reached `healthy` (Docker healthcheck on
  `/health`).
- `GET /health` → `{"status":"ok",...}`.
- `POST /ingest/event` → `{"ok":true,"id":1}`; `GET /events` returned the event;
  `GET /` served the dashboard (HTTP 200, `text/html`).
- Scratch DB created inside the mounted directory as expected.

## Honest limits

- No reverse proxy or TLS is included. Do not expose port 8000 to the internet.
- The healthcheck only proves the process answers `/health`; it does not check
  database integrity or WebSocket clients.
- No multi-worker or Postgres setup. SQLite is fine for a demo, not for a
  multi-node production deployment.
- The container was tested on one laptop with Docker via Colima. It has not been
  run on a cloud VM.

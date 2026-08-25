# Todo API

A minimal FastAPI sample service secured with an `X-API-KEY` header.

## Run locally

```bash
pip install -r requirements.txt
cp .env.example .env   # set a strong API_KEY
uvicorn app:app --reload
```

## Run with Docker

```bash
docker build -t todo-api .
docker run --rm -p 8000:8000 --env-file .env todo-api
```

## Notes / limitations

- **Storage is in-memory** — todos are lost on every restart. This is a demo
  service, not a production datastore. Swap `crud.py` for SQLite/Postgres for
  real use.
- Call the API over **HTTPS in any real deployment** (put it behind a reverse
  proxy such as Caddy/nginx/Traefik); the key is sent as a plain header.
- Requests are authenticated with a constant-time API-key comparison. There is
  deliberately no rate limiting — add it (e.g. `slowapi`) before exposing this
  publicly.

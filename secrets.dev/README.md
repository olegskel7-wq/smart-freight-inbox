# secrets.dev — DEV secrets only (Gate C5)

One file per secret, file name = variable name, value without a trailing newline. These are the
same public dev defaults docker-compose.yml used to fall back to, so a fresh clone runs with no
setup. **Never put a real value here** — this directory is in git.

Real secrets live OUTSIDE the working tree, in the directory named by `SECRETS_DIR` in `.env`
(server: `/etc/smartfreight/secrets`). How to create, migrate and rotate them:
[`docs/ops/secrets.md`](../docs/ops/secrets.md). `scripts/check-prod-env.sh` refuses a server whose
`SECRETS_DIR` is this directory or anywhere inside the repo.

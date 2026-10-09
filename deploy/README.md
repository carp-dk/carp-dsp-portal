# Deploying the portal

The portal as one image file, for a server with no registry and no internet.
The environments the UbiLife workflows need are built into the image, so runs
never download packages.

## Build the package (on a machine with Docker and internet)

Commit everything in the four checkouts, then from `carp-dsp-portal`:

```powershell
powershell -ExecutionPolicy Bypass -File deploy\package.ps1 -Tag 2026.10.08
```

It builds from clean clones of those commits, with no cache, and writes
`carp-dsp-release\carp-dsp-portal-<tag>.tar.gz` beside the checkouts:

| File | What it is |
|---|---|
| `image.tar` | The image, for `docker load` |
| `docker-compose.yml` | Runs the image, state on a named volume |
| `.env.example` | Database and memory settings, `TAG` filled in |
| `bundles/*.zip` | The UbiLife workflows, to upload |
| `VERSIONS.txt` | The commit of each checkout |
| `SHA256SUMS` | Checksums of the above |

## Install (on the server)

```sh
tar -xzf carp-dsp-portal-<tag>.tar.gz
cd carp-dsp-portal-<tag>
sha256sum -c SHA256SUMS
docker load -i image.tar
cp .env.example .env        # fill in the database settings
docker compose up -d
docker compose logs -f      # "Copying prebuilt environment ..." on first start
curl -fsS http://127.0.0.1:8080/health
```

Then upload the bundles and set their time parameters and schedules as each
bundle's README says.

## Keep it safe

- The port is bound to `127.0.0.1`. Reach it through the server's reverse proxy
  with access control: whoever reaches the portal can run code on a machine
  holding database credentials.
- Use a read-only database role.
- Credentials live in `.env` only, never in the image or a bundle.

## Upgrade

Load the new image, set `TAG` in `.env`, and `docker compose up -d`. Runs,
schedules and uploads stay on the volume; new environments are copied onto it
at start.

## Notes

- A workflow whose environment is not built into the image cannot run here: it
  would need to download packages. Add its bundle to `PREBUILT_ENVS` in the
  `Dockerfile` and rebuild.
- The image is built for `linux/amd64`.

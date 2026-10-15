---
id: tls
title: HTTPS (TLS)
---

# HTTPS (TLS)

The observe server can serve HTTPS itself, with no proxy in front. Every request then carries the API key, passwords and the dashboard's session cookie encrypted, and the live WebSocket stream switches to WSS with the same certificate.

Without TLS the server still starts, but it logs a `WARN` on every start:

```
Serving plain HTTP on 0.0.0.0:9464: API keys, passwords and session cookies cross the network unencrypted. ...
```

## On your machine: `nexora tls-gen`

```bash
nexora tls-gen --out ./certs
nexora observe --tls-cert ./certs/server.pem --tls-key ./certs/server-key.pem
```

`tls-gen` writes three files:

| File | What it is | Who needs it |
|---|---|---|
| `ca.pem` | A certificate authority made for this run | Anything that should trust the server: curl, the CLI, your browser |
| `server.pem` | The server's certificate, signed by that CA | `--tls-cert` |
| `server-key.pem` | The server's private key, readable only by you | `--tls-key` |

The server certificate is valid for `localhost`, `127.0.0.1` and `::1`, for 365 days. Add names or addresses with `--host` (repeat it), and change the validity with `--days` (at most 825, the limit Apple platforms enforce). Existing files are kept unless you pass `--force`.

The CA's own private key is thrown away as soon as the server certificate is signed. Once you trust `ca.pem`, nobody, you included, can use it to sign a certificate for some other site. Run `tls-gen` again for a new set.

Why a CA and not a single self-signed certificate: Firefox refuses a certificate that is both a CA and the server's own, and curl and OpenSSL refuse one that is not a CA as something to trust. A small CA plus a server certificate works with all of them, which is how `mkcert` does it too.

### Trusting `ca.pem`

```bash
# curl
curl --cacert ./certs/ca.pem https://localhost:9464/health/live

# the Nexora CLI
nexora login  --server https://localhost:9464 --cacert ./certs/ca.pem
nexora cancel <id> --server https://localhost:9464 --cacert ./certs/ca.pem
```

`login`, `logout`, `cancel` and `replay` all take `--cacert`. Without it they use the JVM's trusted authorities, and a certificate those do not know fails with a message that names `--cacert`.

For a browser, add `ca.pem` as a trusted authority:

- **macOS** (Safari, Chrome, Edge): `sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain ./certs/ca.pem`
- **Linux, system tools**: `sudo cp ./certs/ca.pem /usr/local/share/ca-certificates/nexora-dev.crt && sudo update-ca-certificates`
- **Chrome on Linux**: `chrome://settings/certificates`, then **Authorities** and **Import**
- **Firefox**: Settings, then **Privacy & Security**, **View Certificates**, **Authorities** and **Import**
- **Windows**: `certutil -addstore -f ROOT certs\ca.pem` in an administrator prompt

Remove it again the same way when you are done. `tls-gen` certificates are for development only.

## In production: a real certificate

Any certificate from a public or company CA works. Pass the certificate with its chain and the unencrypted private key.

### Let's Encrypt

```bash
sudo certbot certonly --standalone -d nexora.example.com
nexora observe \
  --tls-cert /etc/letsencrypt/live/nexora.example.com/fullchain.pem \
  --tls-key  /etc/letsencrypt/live/nexora.example.com/privkey.pem
```

- Use `fullchain.pem`, not `cert.pem`. Clients need the intermediate certificate as well as the server's own.
- `privkey.pem` is readable only by root. Rather than running Nexora as root, copy both files to a directory the service user can read in a certbot `--deploy-hook`, and restart Nexora there.
- **The certificate is read at start.** Restart the server after every renewal to serve the new one. The server logs a `WARN` at start when its certificate expires within 14 days, or already has.

### PKCS12 keystore

If your certificate comes as a `.p12` / `.pfx` file, or you keep keys in a Java keystore, pass it instead of the PEM files:

```bash
export NEXORA_KEYSTORE_PASSWORD='...'
nexora observe --keystore /etc/nexora/server.p12
```

`--keystore-password` works too, but command-line flags are visible to other users in the process list. The key entry must use the keystore's password, as `openssl pkcs12 -export` and `keytool` do by default. To build one from PEM files:

```bash
openssl pkcs12 -export -in fullchain.pem -inkey privkey.pem -out server.p12
```

### Key formats

Private keys may be unencrypted PKCS#8 (`-----BEGIN PRIVATE KEY-----`, what certbot and OpenSSL 3 write) or PKCS#1 RSA (`-----BEGIN RSA PRIVATE KEY-----`). Use an RSA or EC (P-256) key. Ed25519 keys load, but browsers and some TLS clients (macOS curl among them) cannot use an Ed25519 certificate. An encrypted key or an `EC PRIVATE KEY` is refused with the command that converts it:

```bash
openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem
```

The server checks at start that the key belongs to the first certificate in `--tls-cert`, so a swapped or stale file fails at start, not on the first browser visit.

## What changes with TLS on

- **Ports.** HTTPS stays on `--port` (9464) and WSS on the next port (9465), both with the same certificate. Open both in a firewall.
- **Protocols.** TLS 1.2 and 1.3 only. TLS 1.0 and 1.1 are refused (RFC 8996). Cipher suites are the JDK's defaults.
- **Session cookie.** It gains the `Secure` attribute, so the browser never sends it over plain HTTP, even to the same host.
- **Startup banner.** The URLs switch to `https://` and `wss://`, and a `TLS:` line names the certificate and its expiry date.
- **Plain HTTP on the TLS port** gets an empty reply: the connection is closed, nothing is served in cleartext.

## Behind a TLS proxy instead

Terminating TLS in nginx, Traefik or a cloud load balancer still works. Start Nexora without TLS options and send the proxy's `https://` URL to the CLI. The `WARN` still appears, since the hop from the proxy to Nexora is plain HTTP; keep that hop on localhost or a private network. The proxy must forward the WebSocket port as well, and must not rewrite the `Host` header (see the [authentication limits](authentication.md#limits-to-know)).

## When something is refused

| Symptom | Cause | Fix |
|---|---|---|
| `Error: --tls-cert and --tls-key must be given together` | Only one of the two was passed | Pass both |
| `does not belong to the first certificate` | The key is for another certificate, or the file lists the chain before the server's own certificate | Use the matching key, and `fullchain.pem` with the server's certificate first |
| `is encrypted` / `is a SEC1 EC key` | Key format the server does not read | Convert it with `openssl pkcs8 -topk8 -nocrypt` |
| `Wrong password for the keystore` | `NEXORA_KEYSTORE_PASSWORD` or `--keystore-password` is wrong | Fix the password |
| curl: `unable to get local issuer certificate` | curl does not trust the CA | `--cacert ./certs/ca.pem` |
| CLI: `could not verify the TLS certificate` | The JVM does not trust the CA | `--cacert ./certs/ca.pem` |
| Browser: "Your connection is not private" | The browser does not trust the CA, or the host name is not on the certificate | Trust `ca.pem`, or run `tls-gen --host <name>` |
| Dashboard says "Disconnected" | The WebSocket port is blocked, or the browser has not accepted the certificate for that port | Open port 9465; trust the CA rather than clicking through a warning |

## Limits to know

- **No reload.** A new certificate takes a restart.
- **No HSTS.** The server does not send `Strict-Transport-Security`. On `localhost` it would force HTTPS on every other local app the browser visits, whatever the port. Set it in a proxy if you need it.
- **No client certificates.** Callers authenticate with API keys and passwords, not mutual TLS.

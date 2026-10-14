---
id: authentication
title: Authentication
---

# Authentication

The observe server (`nexora observe`) and the payment pipeline demo protect their API with API keys. There are only two kinds of key, and every way of signing in ends with one of them.

## Two kinds of key

| | Server key (`nxk_...`) | User key (`nxu_...`) |
|---|---|---|
| What it is | The master key for one server | A temporary key for one person |
| Where it comes from | Printed when the server starts, or set with `NEXORA_API_KEY` | Created when someone signs in with a correct username and password |
| Who uses it | Admins, scripts, CI | People, in the dashboard or with `nexora login` |
| How long it lasts | Until you rotate it | 12 hours, or until logout, a password change, user removal or a server restart |
| Audit log shows | `principal=api-key` | `principal=alice` |

Both are sent the same way, `Authorization: Bearer <key>`, and both unlock the same routes, with one exception: only the server key or a password can open a dashboard session (`POST /api/session`). A user key cannot mint another session, so one stolen key cannot renew itself; to sign in to the dashboard, use your username and password.

## What is locked

```
Public (no key)                     Locked (needs a key)
───────────────                     ────────────────────
/  (the dashboard page)             /api/*      every API call
/health/*  (Kubernetes probes)      WebSocket   the live stream on port + 1
/metrics   (Prometheus)
/docs, /openapi.yaml
```

The dashboard page loads for anyone, but stays empty until you sign in, because all of its data comes from `/api/*`. Probes and scrapers cannot carry credentials, which is why those routes stay public.

## Starting the server

```
nexora observe
   │
   ├─ NEXORA_API_KEY set? ────────▶ use it; nothing is printed or saved
   │
   ├─ a saved key from before? ───▶ use it; only its SHA-256 hash was saved,
   │                                so it cannot be shown again
   │
   └─ neither ────────────────────▶ generate nxk_..., print it once,
                                    save only its hash
```

The generated key's hash lives in `~/.nexora/observe-api-key.sha256`, readable only by you (change the path with `--api-key-file`). The startup banner says which case applied:

```
Auth:      /api/* requires Authorization: Bearer <key>; the UI logs in with a username and password, or the key
           Key: from NEXORA_API_KEY
Users:     1 dashboard user(s) in /home/you/.nexora/observe-users; manage with `nexora user`
```

`NEXORA_API_KEY` must be at least 32 characters. `--api-key` works too, but command-line flags are visible to other users in the process list.

## Signing in to the dashboard

```
Browser                                       Server
───────                                       ──────
username + password ──── POST /api/session ──▶ check the password
  (or paste the server key)                       │
                                                  ├─ wrong ─▶ "Wrong username or password"
                                                  │           (5 misses lock the username for 15 minutes)
                                                  │
                                                  └─ right ─▶ create a user key nxu_...
                      ◀── Set-Cookie: nxu_... ──              and send it as a cookie
browser keeps the cookie; every later /api call
and the WebSocket send it automatically
```

The browser only ever receives the key as an `HttpOnly; SameSite=Strict` cookie, so script running in the page cannot read it, even if some were injected. Requests that change something, and the WebSocket, must also come from the dashboard's own address (its `Origin`), so a page served from another port or site cannot use your session.

The nav bar shows who is signed in, and **Log out** revokes the key.

## Signing in from the CLI

```
nexora login                              Server
────────────                              ──────
asks for username + password ── POST /api/keys ──▶ check the password
                             ◀── { "apiKey": "nxu_..." } ── same kind of key, in the body
saves it to ~/.nexora/credentials (one per server, readable only by you)

nexora cancel <id>   sends the saved key automatically
nexora replay <id>   sends the saved key automatically
nexora logout        revokes the key on the server and deletes the local copy
```

`POST /api/session` and `POST /api/keys` check passwords the same way. The only difference is delivery: a cookie for browsers, the response body for the CLI.

`nexora cancel` and `nexora replay` pick a key in this order: `--api-key`, then `NEXORA_API_KEY`, then the key saved by `nexora login`.

## Scripts and CI

No sign-in step. Give the server a key through the environment and send the same value:

```bash
export NEXORA_API_KEY="$(openssl rand -base64 32)"
nexora observe &
curl -H "Authorization: Bearer $NEXORA_API_KEY" http://localhost:9464/api/process
```

## Managing users

```bash
nexora user add alice       # asks for the password twice; alice can sign in right away
nexora user passwd alice    # new password; alice's existing keys stop working
nexora user remove alice    # deleted; alice's keys stop working
nexora user list
```

For scripts, pass the password on the first line of standard input with `--password-stdin`, never as an argument:

```bash
printf '%s\n' "$ALICE_PASSWORD" | nexora user add alice --password-stdin
```

- Passwords must be 12 to 128 characters and must not be the username.
- They are never stored: each is hashed with PBKDF2-HMAC-SHA256 (600,000 iterations, a random salt per user) into `~/.nexora/observe-users`, readable only by you. Use `--users-file` on both `nexora user` and `nexora observe` to put it elsewhere.
- Changes reach a running server immediately; no restart is needed.

## Rotating the server key

Restart with `nexora observe --rotate-api-key`. A new key is printed, the stored hash is replaced, and the old key stops working. With `NEXORA_API_KEY`, change the variable and restart. Lost the key? Rotate it; user sign-in keeps working meanwhile.

## Try it: the login demo

`tests/login-demo` runs the real observe server with two demo users, so you can try every flow on this page without setting anything up:

```bash
mvn -q install -DskipTests
java -jar tests/login-demo/target/login-demo.jar
# or run com.nexora.samples.login.LoginDemoApp from your IDE
```

It listens on `http://localhost:9470` (localhost only) and prints the users, a server key for the run, and a guided tour:

| User | Password |
|---|---|
| `alice` | `alice-demo-password` |
| `bob` | `bob-demo-password` |

1. **Dashboard sign-in:** sign in as alice and run "process order"; the console logs it as `principal=alice`.
2. **Lockout:** five wrong passwords for bob, and the sixth attempt is refused for 15 minutes.
3. **CLI:** the CLI defaults to port 9464, so point every command at the demo with `--server`:

   ```bash
   nexora login  --server http://localhost:9470 -u bob
   nexora cancel <executionId> --server http://localhost:9470
   nexora logout --server http://localhost:9470
   ```

   The demo's own console prints these with a `--credentials-file` inside its temp folder, so they leave your real `~/.nexora/credentials` alone.
4. **curl:** mint a user API key from `POST /api/keys` and use it as a Bearer header.
5. **Live revocation:** `nexora user remove alice --users-file <path printed by the demo>` while she is signed in, and the dashboard asks her to sign in again.

Every sign-in, rejection and change appears on the demo's console as an `audit` line. The users, their keys and the demo's CLI login file live in a temp folder that is deleted when you stop the demo. The passwords are printed on purpose: never reuse them.

## The payment pipeline demo

The demo (`tests/payment-pipeline`) uses the same rules with two differences: it saves nothing, and it has no user accounts. So its sign-in card only asks for the server key.

1. Start the demo, for example from your IDE.
2. Find the key in its console output, near the top:

   ```
   Auth:      /api/* and the WebSocket require the API key; the UI asks for it once.
              Key for this run (not saved; set NEXORA_API_KEY to choose your own):
              nxk_...
   ```

3. Paste that `nxk_...` value into the sign-in card.

A new key is generated on every start. To keep the same one, set `NEXORA_API_KEY` (32+ characters) in your run configuration. `/api/mock-webhook` stays public: it is the demo's webhook receiver, and Nexora's webhook delivery calls it with an HMAC signature, not an API key.

## When something is refused

| Response | Meaning | What to do |
|---|---|---|
| `401` `UNAUTHORIZED` | No key, a wrong key, or an expired or revoked user key | Sign in again, or check `NEXORA_API_KEY` / `--api-key` |
| `429` `TOO_MANY_ATTEMPTS` | 5 wrong passwords for that username (locked 15 minutes), or over 20 sign-in attempts a minute from your address | Wait for the time in `Retry-After`, or use the server key |
| `415` `UNSUPPORTED_MEDIA_TYPE` | Sign-in credentials not sent as `application/json` | Send a JSON body |
| WebSocket closes at once | The socket was opened without a key or session | Sign in to the dashboard first |

Responses never say whether a username exists: an unknown name is rejected, timed and locked exactly like a wrong password.

## Limits to know

- **Plain HTTP.** Until TLS lands ([#40](https://github.com/iamvirul/nexora/issues/40)), keys and passwords cross the network unencrypted. Keep the server on localhost or a trusted network, or put a TLS proxy in front of it and use an `https://` URL. The CLI (`login`, `logout`, `cancel`, `replay`) **refuses** to send a password or key over plain HTTP to another machine unless you pass `--allow-insecure-http`; localhost is always allowed.
- **Restarts.** User keys live in the server's memory, so a restart signs everyone out. The server key and user accounts are on disk and survive.
- **Reverse proxies.** Dashboard requests that change something must carry an `Origin` matching the address they were sent to. A proxy that rewrites the `Host` header breaks this; Bearer keys are unaffected.

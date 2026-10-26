# Run the demo from zero

From an empty x86_64 machine to an Oracle Forms screen showing a live Vaadin
view. Two repositories are involved:

- [forms-docker-environment](https://github.com/jcgueriaud1/forms-docker-environment):
  the Oracle Forms 12.2.1.4 server and the Oracle database, in Docker.
- this repository: the demo's database schema, form, bean and Vaadin view.

Each step ends with a **check**. Don't go on until it passes; the
[troubleshooting](#troubleshooting) table lists what we've hit so far.

## Time budget

| Step | Measured on an Intel Mac (i7-9750H, 16 GB) |
| --- | --- |
| Downloading the Oracle installers (~4.9 GB) | depends on your connection |
| Building the images | not measured yet (please record it, see the end of this page) |
| First start of the Forms server | ~10 minutes |
| Deploying the demo (schema, bean, form) | ~2 minutes |
| First start of the Forms client (downloads JCEF, ~300 MB) | ~40 seconds |

## 1. Before you start

**Machine**
- **x86_64 (Intel or AMD) only.** Oracle Forms 12.2.1.4 has no ARM build, so a
  Mac with Apple Silicon (M1, M2…) can't run the server (see #3).
- Verified: macOS on Intel with Docker Desktop. Linux x86_64 should work the
  same way. On Windows, use Docker Desktop with WSL 2 and run the shell scripts
  in WSL; the Forms client has a Windows launcher (untested).
- **Memory:** give Docker about **12 GB** (the database uses ~2.3 GB and Forms
  ~5.6 GB). Docker Desktop's default is often lower: Settings → Resources.
- **Disk:** about **30 GB** free for the installers and images.

**Software on the host**
- git, Docker (with `docker compose`), curl
- **JDK 21** (the Forms client and the Vaadin view run on it) and **Maven** 3.6.3+
- `keytool` and `jarsigner` (part of the JDK) to sign the bean

**Accounts**
- **Oracle account:** to download the installers and pull Oracle's Java base
  image. Accept the licence terms when asked.
- **Vaadin account, subscription or trial:** swing-kit is a commercial Vaadin
  product. Get your licence key **before** step 5 (see there).
- **GitHub access** to both repositories, while they are private.

## 2. The Forms server and the database

```bash
git clone https://github.com/jcgueriaud1/forms-docker-environment.git
cd forms-docker-environment
```

1. Download Oracle Fusion Middleware **12.2.1.4** and put the installers in
   place (exact file names in that repository's README):
   `images/fmw-infrastructure/fmw_12.2.1.4.0_infrastructure.jar`,
   `images/formsreports/fmw_12.2.1.4.0_fr_linux64.bin` and
   `images/formsreports/fmw_12.2.1.4.0_fr_linux64-2.zip`.
2. Build the images:
   ```bash
   docker login container-registry.oracle.com
   ./build-images.sh
   ```
   **Check:** `docker image ls 'oracle/*'` lists `oracle/formsreports:12.2.1.4`.
3. Start:
   ```bash
   docker compose up -d
   docker compose logs -f frfmw      # until "Startup complete" (~10 min the first time)
   ```
   **Check:**
   ```bash
   curl -s -o /dev/null -w '%{http_code} %{content_type}\n' \
     'http://localhost:7777/forms/frmservlet?config=test'
   ```
   prints `200 application/x-java-jnlp-file`.

## 3. The demo's schema, bean and form

```bash
cd ..
git clone https://github.com/jcgueriaud1/forms-vaadin-hybrid-demo.git
cd forms-vaadin-hybrid-demo
./setup-db.sh        # check: ends with "schema ready: hybrid/hybrid@XEPDB1"
./deploy-bean.sh     # check: ends with "deployed: …/hybrid-bean.jar"
./deploy-form.sh     # check: ends with "built: …/hybrid_demo.fmx"
```

`deploy-bean.sh` creates a self-signed key in `~/.forms-vaadin-hybrid/` on the
first run, and downloads Oracle's `frmall.jar` from your Forms server to
compile the bean.

**Check:**
```bash
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' \
  'http://localhost:7777/forms/frmservlet?config=hybrid'
```
prints `200 application/x-java-jnlp-file`.

## 4. The Vaadin licence (first time only)

On a machine without a Vaadin licence key, the Vaadin view shows a licence
splash screen, and here it would appear inside the small embedded browser in
Forms. Get the key first:

```bash
mvn com.vaadin:vaadin-maven-plugin:download-license
```

It opens vaadin.com to log in and stores the key in `~/.vaadin/proKey`.
Alternatively, start the view (step 5) and open http://localhost:8080 in a
normal browser once: accept the trial or log in there.

## 5. The Vaadin view

In a separate terminal:

```bash
cd hello-view
mvn spring-boot:run
```

The view connects to the demo schema at `localhost:1521/XEPDB1` as
`hybrid`/`hybrid` (the database port is published by forms-docker-environment).
For another database, set `HYBRID_JDBC_URL`, `HYBRID_DB_USER` and
`HYBRID_DB_PASSWORD`.

**Check:** http://localhost:8080 in a normal browser shows "Waiting for the
host form…" and "Open this view inside the Forms host form to talk to it."

## 6. The Forms client

```bash
./run-forms-client.sh          # macOS / Linux
run-forms-client.cmd           # Windows (untested)
```

The first start downloads JCEF (~300 MB) to `~/.jcef-bundle`.

**Check:** a window "Forms and Vaadin hybrid demo" opens with the customer list
on top and, below it, "Hello, Blue Fjord Outfitters (#103)", "Bergen, Norway
· 3 orders, total 1,053.00 (read by Vaadin from Oracle)" and a grid with three
orders.

## 7. Try it

- Click another customer: the Vaadin view shows its orders.
- Double-click an order, change the quantity (or the date, product or status)
  and click **Save**. The grid updates, and in the Forms list the customer's
  **Orders total** changes to the new value, with "Order saved in Vaadin: orders
  total updated." in the status bar.
- Outside Forms, the same view works in a normal browser at
  http://localhost:8080/?customer=103 (without the Forms update).
- Click **Ask Forms to say hi**: the Forms status bar at the bottom shows
  "Hi from Vaadin, <name>!".

## Stop and start again

- Close the Forms window; stop the view with Ctrl+C.
- `docker compose stop` in `forms-docker-environment`. Always stop cleanly; see
  the troubleshooting table.
- Next time: `docker compose up -d` (2–4 minutes), the view, then the client.
  The deploy steps don't need repeating; the volumes keep everything.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `build-images.sh`: "missing installer" | An installer file is missing or misnamed | Check the names in step 2 |
| Port 7777 never answers; the log shows `TIMEOUT waiting for OHS` | The container was killed instead of stopped, and a stale `httpd.pid` made OHS kill NodeManager | `docker compose restart frfmw`: `frrun.sh` removes the stale file on start. Always stop with `docker compose stop` |
| `…?config=test` returns an HTML page, not a JNLP | The startup hasn't finished | Wait for "Startup complete" |
| `deploy-form.sh`: "ERROR: compile failed" with no message | The form compiler couldn't log in to the database | Check `setup-db.sh` ran; check `HYBRID_DB_HOST`/`HYBRID_DB_SERVICE` if you changed the defaults |
| The client fails with FRM-93136 | The `[hybrid]` section in `formsweb.cfg` lacks `baseSAAfile=basesaa.txt` (only possible after editing it by hand) | Add that line to the section |
| The client hangs on its splash screen (macOS) | JCEF started while the window was moving | Fixed in the bean; restart the client, and don't move the window during the first seconds |
| `IllegalAccessError … sun.awt` | The JVM lacks the `--add-opens` flags | Start through `run-forms-client.sh` / `.cmd`, not plain `java -jar` |
| The bean area shows a browser error page | The Vaadin view isn't running | Start step 5, then restart the client |
| A Vaadin licence screen inside the Forms window | No Vaadin licence key | Step 4 |
| The view shows "Not found in the database." or the greeting never appears; the view's log shows `HikariPool` or `ORA-` errors | The view can't reach the demo schema | Check port 1521 is published and `setup-db.sh` ran; set `HYBRID_JDBC_URL` if your database differs |
| Clicking customers changes nothing in the view | The view isn't connected, or calls are blocked | Look at `~/hybrid-bean.log` |
| Accented names show without accents | Missing `NLS_LANG` | Re-run `./deploy-form.sh` |

## Report back (acceptance test for #1)

If you're the first person to follow this guide on a new machine, add a
comment to issue #1 with:

- machine (CPU, RAM, OS) and Docker version
- time to build the images, and to the first "Startup complete"
- every step where you got stuck or had to do something not written here
- whether steps 6 and 7 worked (a screenshot helps)

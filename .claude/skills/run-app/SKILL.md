# run-app

Manage the logistics-ted Spring Boot application: start, stop, restart, and check status.

## Description

Handles the full lifecycle of the logistics-ted app running on port 3002.
PostgreSQL must already be running (scoop install — start manually if needed).

## Invocation

Use this skill when the user says any of:
- "start the app", "run the app", "launch the app"
- "restart the app", "restart", "reload"
- "stop the app", "kill the app"
- "is the app running?", "app status", "check the app"
- after making code changes that need a restart

---

## Environment

| Variable | Value |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/logistics-ted` |
| `DB_USER` | `postgres` |
| `DB_PASSWORD` | _(empty)_ |
| `ROUTING_GOOGLE_API_KEY` | `placeholder` |
| `TEDHOUSE_JWT_SECRET` | `devsecretdevsecretdevsecretdevsecret` |

App runs on **http://localhost:3002**  
Project root: `C:/Users/itqpl/Claude cowork/logistics-ted`

PostgreSQL (scoop) start command if DB is down:
```bash
pg_ctl start -D "/c/Users/itqpl/scoop/apps/postgresql/current/data"
```

---

## Commands

### Check status
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:3002/
```
- `200` or `302` → app is up
- `000` or connection refused → app is down

### Stop
```bash
pkill -f "spring-boot:run" 2>/dev/null
pkill -f "logistics-ted"   2>/dev/null
echo "stopped"
```

### Start
```bash
cd "C:/Users/itqpl/Claude cowork/logistics-ted" && \
DB_URL=jdbc:postgresql://localhost:5432/logistics-ted \
DB_USER=postgres \
DB_PASSWORD= \
ROUTING_GOOGLE_API_KEY=placeholder \
TEDHOUSE_JWT_SECRET=devsecretdevsecretdevsecretdevsecret \
./mvnw spring-boot:run > app2.log 2>app2err.log &
echo "Started PID $!"
```

Then wait for ready:
```bash
until curl -s -o /dev/null -w "%{http_code}" http://localhost:3002/ \
  | grep -q "200\|302"; do sleep 3; done && echo "App ready at http://localhost:3002"
```
Use `run_in_background: true` on the wait command; it notifies when done.

### Restart
Run **Stop** first, then **Start**.

### Check logs (last 20 lines)
```bash
tail -20 "C:/Users/itqpl/Claude cowork/logistics-ted/app2.log"
tail -20 "C:/Users/itqpl/Claude cowork/logistics-ted/app2err.log"
```

---

## Startup time

Cold start (first run / after `mvnw clean`): ~30–45 s  
Warm start (cached classes): ~15–20 s

If startup fails, check `app2err.log` for:
- `Unable to acquire JDBC Connection` → PostgreSQL is not running
- `Port 3002 already in use` → another process is on the port; run Stop first
- `Flyway checksum mismatch` → a migration file was edited; requires manual DB reset

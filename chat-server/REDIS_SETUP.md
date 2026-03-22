# Redis Setup Guide for VPS

## 1. Install Redis

### Ubuntu / Debian

```bash
sudo apt update
sudo apt install redis-server -y
```

### CentOS / RHEL

```bash
sudo yum install epel-release -y
sudo yum install redis -y
```

### Amazon Linux 2

```bash
sudo amazon-linux-extras install redis6 -y
```

## 2. Configure Redis

Open the config file:

```bash
sudo nano /etc/redis/redis.conf
```

Make these changes:

```
# Bind to localhost only (secure)
bind 127.0.0.1

# Run as background service
daemonize yes

# Set max memory (adjust based on your VPS RAM)
maxmemory 128mb
maxmemory-policy allkeys-lru

# Disable persistence (optional - our data is cached, not critical)
save ""
appendonly no
```

Save and exit (`Ctrl+X`, then `Y`, then `Enter`).

## 3. Start Redis

```bash
sudo systemctl start redis
sudo systemctl enable redis
```

## 4. Verify Redis is Running

```bash
redis-cli ping
```

Expected output:

```
PONG
```

Check status:

```bash
sudo systemctl status redis
```

## 5. Configure Chat Server

Edit `chat-server/.env` and set the Redis URL:

```
REDIS_URL=redis://127.0.0.1:6379
```

This is the default value, so if Redis is on the same VPS on the default port, no changes are needed.

## 6. Restart Chat Server

```bash
# If using pm2
pm2 restart chat-server

# If using systemd
sudo systemctl restart chat-server

# If running directly
cd /path/to/chat-server
node index.js
```

## 7. Verify Redis Connection

Check the server health endpoint:

```bash
curl http://localhost:3500/health
```

Look for `"redis": "connected"` in the response. If it shows `"unavailable"`, Redis is not running or not reachable.

## Useful Redis Commands

```bash
# Check memory usage
redis-cli info memory

# Monitor live commands (for debugging)
redis-cli monitor

# View all cached keys
redis-cli keys "*"

# Flush all cache (safe - data rebuilds from DB)
redis-cli flushall

# Check a specific key
redis-cli get "convlist:1"
```

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `redis-cli: command not found` | Redis not installed. Run install step again. |
| `Could not connect to Redis` | Run `sudo systemctl start redis` |
| Health shows `"redis": "unavailable"` | Check Redis is running: `redis-cli ping` |
| High memory usage | Lower `maxmemory` in redis.conf and restart |

## Notes

- The chat server works **without Redis** -- it falls back to SQLite for all operations. Redis only adds speed.
- All cached data has TTLs (30s for conversation lists, 5min for contacts, 24h for presence). No manual cleanup needed.
- If you ever need to clear the cache, run `redis-cli flushall`. The server will rebuild cache automatically from the database.

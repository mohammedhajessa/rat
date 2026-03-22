# Chat Server - VPS Deployment Guide

## Requirements

- **OS**: Ubuntu 20.04+ / Debian 11+ (recommended)
- **RAM**: 1GB minimum (2GB recommended)
- **Node.js**: v18+ (v20 LTS recommended)
- **Ports**: 3500 (or your chosen port)

---

## 1. Prepare the VPS

```bash
sudo apt update && sudo apt upgrade -y

curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs

node -v
npm -v

sudo apt install -y build-essential python3
```

---

## 2. Upload Files to VPS

### Option A: SCP (from your local machine)

```bash
scp -r ./chat-server root@YOUR_VPS_IP:/opt/chat-server
```

### Option B: Git

```bash
cd /opt
git clone YOUR_REPO_URL chat-server
```

### Option C: SFTP (FileZilla / WinSCP)

Connect via SFTP and upload chat-server folder to /opt/chat-server

### Files to upload

```
chat-server/
 index.js
 package.json
 package-lock.json
 .env
 db/
   database.js
 routes/
   auth.js
   contacts.js
   messages.js
   upload.js
   calls.js
   block.js
   mute.js
 socket/
   chatHandler.js
   callHandler.js
 services/
   messageService.js
   callService.js
   s3Service.js
 middleware/
   rateLimit.js
   validate.js
   errorHandler.js
```

**DO NOT upload**: 
ode_modules/ and db/chat.db

---

## 3. Install Dependencies

```bash
cd /opt/chat-server
npm install --production
```

---

## 4. Configure Environment

```bash
nano /opt/chat-server/.env
```

```env
PORT=3500
NODE_ENV=production

AWS_ACCESS_KEY_ID=your_access_key
AWS_SECRET_ACCESS_KEY=your_secret_key
AWS_REGION=us-east-1
AWS_BUCKET_NAME=your-bucket-name

CORS_ORIGINS=*
```

---

## 5. Test Run

```bash
cd /opt/chat-server
node index.js
```

Press Ctrl+C to stop after confirming it works.

---

## 6. Run with PM2 (Process Manager)

```bash
sudo npm install -g pm2

cd /opt/chat-server
pm2 start index.js --name chat-server

pm2 save
pm2 startup

# Useful commands:
# pm2 status
# pm2 logs chat-server
# pm2 restart chat-server
# pm2 stop chat-server
# pm2 monit
```

---

## 7. Nginx Reverse Proxy

```bash
sudo apt install -y nginx
sudo nano /etc/nginx/sites-available/chat-server
```

Paste:

```nginx
server {
    listen 80;
    server_name your-domain.com;

    location / {
        proxy_pass http://127.0.0.1:3500;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }
}
```

Enable:

```bash
sudo ln -s /etc/nginx/sites-available/chat-server /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl restart nginx
```

---

## 8. SSL with Let's Encrypt

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d your-domain.com
sudo certbot renew --dry-run
```

---

## 9. Firewall

```bash
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
```

---

## 10. Update Android App

Change server URL in:
ndroid-native-app/app/src/main/java/com/h4ckinto/android/utils/Constants.java

```java
public static final String CHAT_SERVER_URL = "https://your-domain.com";
```

---

## Updating the Server

```bash
cd /opt/chat-server
# Upload new files
npm install --production
pm2 restart chat-server
```

---

## Monitoring

```bash
pm2 logs chat-server
curl http://localhost:3500/health
pm2 monit
```

---

## Backup Database

```bash
cp /opt/chat-server/db/chat.db /opt/backups/chat-$(date +%Y%m%d).db

# Auto backup (crontab -e):
# 0 3 * * * cp /opt/chat-server/db/chat.db /opt/backups/chat-$(date +\%Y\%m\%d).db
```

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| better-sqlite3 install fails | sudo apt install -y build-essential python3 |
| Port already in use | lsof -i :3500 then kill -9 PID |
| WebSocket not connecting | Check Nginx Upgrade/Connection headers |
| Server crashes | Check pm2 logs chat-server |
| Database locked | Stop all Node processes, restart |

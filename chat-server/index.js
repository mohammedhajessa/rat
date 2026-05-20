require('dotenv').config();

var express = require('express');
var http = require('http');
var { Server } = require('socket.io');
var cors = require('cors');
var helmet = require('helmet');
var compression = require('compression');

var { getDb } = require('./db/database');
var { setupChatSocket } = require('./socket/chatHandler');
var { setupCallSocket } = require('./socket/callHandler');
var { centralErrorHandler } = require('./middleware/errorHandler');
var { apiLimiter } = require('./middleware/rateLimit');
var callService = require('./services/callService');
var redisService = require('./services/redisService');

var authRoutes = require('./routes/auth');
var contactsRoutes = require('./routes/contacts');
var messagesRoutes = require('./routes/messages');
var uploadRoutes = require('./routes/upload');
var callsRoutes = require('./routes/calls');
var blockRoutes = require('./routes/block');
var muteRoutes = require('./routes/mute');
var favoriteRoutes = require('./routes/favorite');
var archiveRoutes = require('./routes/archive');
var updateRoutes = require('./routes/update');
var filesRoutes = require('./routes/files');
var path = require('path');

var PORT = process.env.PORT || 3500;

// CORS configuration
var allowedOrigins = process.env.CORS_ORIGINS
    ? process.env.CORS_ORIGINS.split(',').map(function(s) { return s.trim(); })
    : ['*'];

var corsOptions = {
    origin: function(origin, callback) {
        // Allow requests with no origin (mobile apps, curl, etc.)
        if (!origin) return callback(null, true);
        if (allowedOrigins[0] === '*' || allowedOrigins.indexOf(origin) !== -1) {
            return callback(null, true);
        }
        callback(new Error('Not allowed by CORS'));
    },
    methods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
    allowedHeaders: ['Content-Type', 'Authorization'],
    credentials: true
};

// Initialize Express
var app = express();
app.set('trust proxy', 1);
var server = http.createServer(app);

// Initialize Socket.IO
var io = new Server(server, {
    cors: { origin: '*' },
    pingTimeout: 30000,
    pingInterval: 15000,
    maxHttpBufferSize: 5e6,
    transports: ['websocket', 'polling'],
    allowEIO3: true,
    perMessageDeflate: { threshold: 2048 },
    httpCompression: true
});

app.use(compression({ level: 6, threshold: 1024 }));
app.use(helmet({
    crossOriginResourcePolicy: { policy: 'cross-origin' },
    contentSecurityPolicy: false
}));
app.use(cors(corsOptions));
app.use(express.json({ limit: '5mb' }));

// Global rate limiter
app.use('/api/', apiLimiter);

// Initialize database
getDb();

// Initialize Redis (optional - graceful fallback if unavailable)
try { redisService.getRedis(); } catch (e) { console.log('[REDIS] Not available, running without cache'); }

// REST API Routes (same paths as before)
app.use('/api/auth', authRoutes);
app.use('/api/contacts', contactsRoutes);
app.use('/api/messages', messagesRoutes);
app.use('/api/upload', uploadRoutes);
app.use('/api/calls', callsRoutes);
app.use('/api/block', blockRoutes);
app.use('/api/mute', muteRoutes);
app.use('/api/favorite', favoriteRoutes);
app.use('/api/archive', archiveRoutes);
app.use('/api/app', updateRoutes);
app.use('/api/files', filesRoutes);

// Serve uploaded files (APK, etc.) from server disk
var uploadsDir = path.join(__dirname, 'uploads');
app.use('/uploads', express.static(uploadsDir));

app.get('/health/live', function(req, res) {
    res.json({ status: 'ok' });
});

app.get('/health/ready', async function(req, res) {
    var checks = { db: false, redis: false };
    try { getDb().prepare('SELECT 1').get(); checks.db = true; } catch (e) {}
    try { if (redisService.available()) { await redisService.getRedis().ping(); checks.redis = true; } } catch (e) {}
    var allOk = checks.db;
    res.status(allOk ? 200 : 503).json({ status: allOk ? 'ready' : 'degraded', checks: checks });
});

app.get('/health', async function(req, res) {
    var dbOk = false;
    try { getDb().prepare('SELECT 1').get(); dbOk = true; } catch (e) {}
    var redisOk = false;
    try { if (redisService.available()) { await redisService.getRedis().ping(); redisOk = true; } } catch (e) {}
    res.json({
        status: dbOk ? 'ok' : 'degraded',
        server: 'secure-chat',
        version: '2.1.0',
        uptime: process.uptime(),
        activeCalls: callService.getActiveCallCount(),
        connections: io.engine.clientsCount,
        memory: Math.round(process.memoryUsage().heapUsed / 1024 / 1024) + 'MB',
        storage: 's3',
        db: dbOk ? 'connected' : 'error',
        redis: redisOk ? 'connected' : 'unavailable'
    });
});

// Admin panel (HTML cached once at startup)
var adminHtml = '<!DOCTYPE html>' +
    '<html lang="en" dir="ltr"><head>' +
    '<meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">' +
    '<title>Chat Admin - PIN Management</title>' +
    '<style>' +
    '* { margin: 0; padding: 0; box-sizing: border-box; }' +
    'body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0a0e1a; color: #e0e0e0; padding: 20px; }' +
    '.container { max-width: 900px; margin: 0 auto; }' +
    'h1 { color: #3B82F6; margin-bottom: 24px; font-size: 24px; }' +
    'h2 { color: #8B9DC3; margin: 20px 0 12px; font-size: 18px; }' +
    '.card { background: #141825; border-radius: 12px; padding: 20px; margin-bottom: 16px; border: 1px solid #1e2538; }' +
    'input, button { padding: 10px 16px; border-radius: 8px; border: 1px solid #2a3450; background: #0d1117; color: #e0e0e0; font-size: 14px; margin-right: 8px; margin-bottom: 8px; }' +
    'input:focus { outline: none; border-color: #3B82F6; }' +
    'button { background: #3B82F6; border-color: #3B82F6; cursor: pointer; font-weight: 600; color: white; }' +
    'button:hover { background: #2563EB; }' +
    'button.danger { background: #EF4444; border-color: #EF4444; }' +
    '.result { background: #0d1117; border-radius: 8px; padding: 12px; margin-top: 12px; font-family: monospace; white-space: pre-wrap; border: 1px solid #1e2538; }' +
    '.pin-display { font-size: 32px; font-weight: bold; color: #10B981; letter-spacing: 8px; text-align: center; padding: 16px; }' +
    'table { width: 100%; border-collapse: collapse; margin-top: 12px; }' +
    'th, td { padding: 10px 12px; text-align: left; border-bottom: 1px solid #1e2538; }' +
    'th { color: #8B9DC3; font-size: 12px; text-transform: uppercase; }' +
    '.online { color: #10B981; } .offline { color: #6B7280; }' +
    '.badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; background: #1e3a5f; color: #60a5fa; margin-left: 8px; }' +
    '</style></head><body>' +
    '<div class="container">' +
    '<h1>Secure Chat - Admin Panel <span class="badge">S3 + WebRTC</span></h1>' +
    '<div class="card"><h2>Generate New Access PIN</h2>' +
    '<div style="display:flex;flex-wrap:wrap;gap:8px;align-items:center;">' +
    '<input type="text" id="displayName" placeholder="Display Name" style="width:200px;">' +
    '<input type="text" id="userRank" placeholder="Rank / \u0627\u0644\u0631\u062A\u0628\u0629" style="width:160px;">' +
    '<input type="text" id="userJobTitle" placeholder="Job Title / \u0627\u0644\u0645\u0633\u0645\u0649 \u0627\u0644\u0648\u0638\u064A\u0641\u064A" style="width:200px;">' +
    '<button onclick="generatePin()">Generate PIN</button></div>' +
    '<div id="pinResult" class="result" style="display:none;"></div></div>' +
    '<div class="card"><h2>Registered Users</h2>' +
    '<button onclick="loadUsers()">Refresh</button>' +
    '<button class="danger" onclick="deleteAllUsers()">Delete All Users</button>' +
    '<div id="usersTable"></div></div>' +
    '<div class="card"><h2>Add Contact (Link Two Users)</h2>' +
    '<div><input type="number" id="userId1" placeholder="User ID 1" style="width: 150px;">' +
    '<input type="number" id="userId2" placeholder="User ID 2" style="width: 150px;">' +
    '<button onclick="addContact()">Add Contact</button></div>' +
    '<div id="contactResult" class="result" style="display:none;"></div></div>' +
    '<div class="card"><h2>Upload Files / Folder</h2>' +
    '<p style="color:#8B9DC3;margin-bottom:12px;font-size:13px;">Upload APK or any files. Saved on server at <code>/uploads/</code>.</p>' +
    '<div style="margin-bottom:8px;"><label style="display:block;margin-bottom:4px;">APK file:</label><input type="file" id="apkInput" accept=".apk,application/vnd.android.package-archive" style="max-width:100%;"></div>' +
    '<div style="margin-bottom:8px;"><label style="display:block;margin-bottom:4px;">Or folder / multiple files:</label><input type="file" id="fileInput" multiple webkitdirectory directory name="files" accept=".apk,application/vnd.android.package-archive,*/*" style="max-width:100%;"></div>' +
    '<button onclick="uploadFiles()">Upload</button>' +
    '<div id="uploadResult" class="result" style="display:none;"></div>' +
    '<div style="margin-top:12px;"><button onclick="loadFileList()">Refresh list</button></div>' +
    '<div id="fileList" style="margin-top:8px;font-size:13px;"></div></div>' +
    '<div class="card"><h2>App Update (Android)</h2>' +
    '<p style="color:#8B9DC3;margin-bottom:12px;font-size:13px;">Set version name (from APK filename, e.g. 2.3.1) and APK URL. App updates only when server version is higher than app version.</p>' +
    '<div style="margin-bottom:10px;"><label>Version name (e.g. 2.3.1 from 2.3.1.apk)</label><br>' +
    '<input type="text" id="upVersionName" placeholder="2.3.1" style="width:120px;"></div>' +
    '<div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:10px;">' +
    '<label>Latest version (versionCode)</label><input type="number" id="upLatest" placeholder="e.g. 33" style="width:120px;">' +
    '<label>Min supported version</label><input type="number" id="upMin" placeholder="e.g. 30" style="width:120px;">' +
    '</div>' +
    '<div style="margin-bottom:10px;"><label>APK URL (full URL to .apk)</label><br>' +
    '<input type="url" id="upApkUrl" placeholder="https://yourserver.com/downloads/app.apk" style="width:100%;max-width:400px;"></div>' +
    '<div style="margin-bottom:10px;"><label>Message (optional)</label><br>' +
    '<input type="text" id="upMessage" placeholder="تحديث أمني" style="width:100%;max-width:400px;"></div>' +
    '<div style="margin-bottom:10px;"><label><input type="checkbox" id="upForce"> Force update (block app if below min)</label></div>' +
    '<button onclick="loadUpdateConfig()">Load current</button><button onclick="saveUpdateConfig()">Save</button>' +
    '<div id="updateResult" class="result" style="display:none;margin-top:8px;"></div></div>' +
    '</div>' +
    '<script>' +
    'var B=window.location.pathname.replace(/\\/admin$/,"");' +
    'async function generatePin(){var n=document.getElementById("displayName").value.trim();var rk=document.getElementById("userRank").value.trim();var jt=document.getElementById("userJobTitle").value.trim();var r=await fetch(B+"/api/auth/generate-pin",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({display_name:n||undefined,rank:rk||undefined,job_title:jt||undefined})});var d=await r.json();var e=document.getElementById("pinResult");e.style.display="block";if(d.success){e.innerHTML=\'<div class="pin-display">\'+d.pin+"</div>User ID: "+d.user_id+"\\nName: "+(d.display_name||"N/A")+"\\nRank: "+(d.rank||"-")+"\\nJob: "+(d.job_title||"-")}else{e.textContent="Error: "+JSON.stringify(d)}loadUsers()}' +
    'async function loadUsers(){var r=await fetch(B+"/api/auth/users");var d=await r.json();if(!d.success)return;var h=\'<table><tr><th>ID</th><th>PIN</th><th>Name</th><th>Username</th><th>Rank</th><th>Job Title</th><th>Status</th></tr>\';d.users.forEach(function(u){var s=u.is_online?\'<span class="online">Online</span>\':\'<span class="offline">Offline</span>\';h+="<tr><td>"+u.id+"</td><td><b>"+u.pin+"</b></td><td>"+u.display_name+"</td><td>"+(u.username||"-")+"</td><td>"+(u.rank||"-")+"</td><td>"+(u.job_title||"-")+"</td><td>"+s+"</td></tr>"});h+="</table>";document.getElementById("usersTable").innerHTML=h}' +
    'async function addContact(){var u1=document.getElementById("userId1").value.trim();var u2=document.getElementById("userId2").value.trim();if(!u1||!u2){alert("Both User IDs are required");return}var r=await fetch(B+"/api/contacts",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({owner_id:parseInt(u1),contact_user_id:parseInt(u2)})});var d=await r.json();var e=document.getElementById("contactResult");e.style.display="block";e.textContent=JSON.stringify(d,null,2)}' +
    'async function deleteAllUsers(){if(!confirm("Are you sure? This will delete ALL data."))return;var r=await fetch(B+"/api/auth/reset",{method:"POST"});var d=await r.json();alert(d.message||JSON.stringify(d));loadUsers()}' +
    'async function loadUpdateConfig(){var r=await fetch(B+"/api/app/update");var d=await r.json();document.getElementById("upVersionName").value=d.version_name||"";document.getElementById("upLatest").value=d.latest_version||"";document.getElementById("upMin").value=d.min_supported_version||"";document.getElementById("upApkUrl").value=d.apk_url||"";document.getElementById("upMessage").value=d.message||"";document.getElementById("upForce").checked=!!d.force_update;var e=document.getElementById("updateResult");e.style.display="block";e.textContent="Loaded."}' +
    'async function saveUpdateConfig(){var r=await fetch(B+"/api/app/update",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({version_name:document.getElementById("upVersionName").value.trim(),latest_version:parseInt(document.getElementById("upLatest").value,10)||1,min_supported_version:parseInt(document.getElementById("upMin").value,10)||1,apk_url:document.getElementById("upApkUrl").value.trim(),message:document.getElementById("upMessage").value.trim(),force_update:document.getElementById("upForce").checked})});var d=await r.json();var e=document.getElementById("updateResult");e.style.display="block";e.textContent=JSON.stringify(d,null,2)}' +
    'async function uploadFiles(){var apkInp=document.getElementById("apkInput");var fileInp=document.getElementById("fileInput");var n=(apkInp.files?apkInp.files.length:0)+(fileInp.files?fileInp.files.length:0);if(n===0){alert("Select an APK file or folder first");return}var fd=new FormData();if(apkInp.files){for(var i=0;i<apkInp.files.length;i++){fd.append("files",apkInp.files[i])}}if(fileInp.files){for(var j=0;j<fileInp.files.length;j++){fd.append("files",fileInp.files[j])}}var e=document.getElementById("uploadResult");e.style.display="block";e.textContent="Uploading...";try{var r=await fetch(B+"/api/files/upload",{method:"POST",body:fd});var text=await r.text();var d;try{d=JSON.parse(text)}catch(_){d={success:false,error:"Invalid response: "+text.substring(0,200)}}if(!r.ok){e.innerHTML="Error "+r.status+": "+(d.error||d.message||text.substring(0,300));return}if(!d.success){e.innerHTML="Error: "+(d.error||JSON.stringify(d));return}e.innerHTML="Uploaded "+d.files.length+" file(s).<br>"+d.files.map(function(f){return \'<a href="\'+f.url+\'" target="_blank">\'+f.filename+\'</a> (\'+f.url+\')\'}).join("<br>");loadFileList();apkInp.value="";fileInp.value=""}catch(err){e.innerHTML="Network error: "+err.message}}' +
    'function useAsApkUrl(url){document.getElementById("upApkUrl").value=url}' +
    'async function loadFileList(){var r=await fetch(B+"/api/files");var d=await r.json();var el=document.getElementById("fileList");if(!d.success){el.innerHTML="Error loading list";return}if(d.files.length===0){el.innerHTML="No files uploaded yet.";return}var esc=function(s){return String(s).replace(/&/g,"&amp;").replace(/"/g,"&quot;").replace(/</g,"&lt;")};var attr=function(s){return String(s).replace(/"/g,"&quot;")};el.innerHTML="<table style=\\"width:100%;font-size:12px;\\"><tr><th>File</th><th>Size</th><th></th></tr>"+d.files.map(function(f){return "<tr><td><a href=\\""+attr(f.url)+"\\" target=\\"_blank\\">"+esc(f.name)+"</a></td><td>"+(f.size>1024*1024?(f.size/1024/1024).toFixed(1)+" MB":(f.size/1024).toFixed(1)+" KB")+"</td><td><button data-url=\\""+attr(f.url)+"\\" onclick=\\"useAsApkUrl(this.getAttribute(\\\'data-url\\\'))\\">Use as APK URL</button> <button class=\\"danger\\" data-name=\\""+attr(f.name)+"\\" onclick=\\"deleteFile(this.getAttribute(\\\'data-name\\\'))\\">Delete</button></td></tr>"}).join("")+"</table>"}' +
    'async function deleteFile(name){if(!confirm("Delete "+name+"?"))return;var r=await fetch(B+"/api/files/"+encodeURIComponent(name),{method:"DELETE"});var d=await r.json();if(d.success)loadFileList();else alert(d.error)}' +
    'loadUsers();loadUpdateConfig();loadFileList();' +
    '</script></body></html>';

app.get('/admin', function(req, res) {
    res.send(adminHtml);
});

// Store io on app so routes can emit events
app.set('io', io);

// Setup Socket.IO handlers
setupChatSocket(io);
setupCallSocket(io);

// Centralized error handler (must be last middleware)
app.use(centralErrorHandler);

function gracefulShutdown(signal) {
    console.log('[SERVER] ' + signal + ' received, shutting down...');
    io.close(function() {
        console.log('[SERVER] Socket.IO closed');
        server.close(function() {
            console.log('[SERVER] HTTP server closed');
            try { getDb().close(); console.log('[SERVER] Database closed'); } catch (e) {}
            try { var r = redisService.getRedis(); if (r) r.disconnect(); } catch (e) {}
            process.exit(0);
        });
    });
    setTimeout(function() { process.exit(1); }, 5000);
}

process.on('SIGTERM', function() { gracefulShutdown('SIGTERM'); });
process.on('SIGINT', function() { gracefulShutdown('SIGINT'); });

// Catch unhandled errors
process.on('uncaughtException', function(err) {
    console.error('[FATAL] Uncaught exception:', err.message);
    console.error(err.stack);
});

process.on('unhandledRejection', function(reason) {
    console.error('[FATAL] Unhandled rejection:', reason);
});

server.timeout = 30000;
server.headersTimeout = 10000;
server.keepAliveTimeout = 65000;

// Start server
server.listen(PORT, '0.0.0.0', function() {
    console.log('==================================================');
    console.log('  Secure Chat Server v2.0 (S3 + WebRTC)');
    console.log('  Port: ' + PORT);
    console.log('  Admin: http://localhost:' + PORT + '/admin');
    console.log('  Health: http://localhost:' + PORT + '/health');
    console.log('  Storage: AWS S3');
    console.log('  Calls: WebRTC signaling via Socket.IO');
    console.log('==================================================');
});

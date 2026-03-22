const low = require('lowdb');
const FileSync = require('lowdb/adapters/FileSync');

// Load database
const adapter = new FileSync('maindb.json');
const db = low(adapter);

// Server IP (your PC IP)
const SERVER_IP = '192.168.1.220';

console.log('🔍 Filtering out devices with same IP as server...');
console.log(`📡 Server IP: ${SERVER_IP}`);

// Get all clients
const clients = db.get('clients').value();
console.log(`📱 Total devices found: ${clients.length}`);

// Filter out devices with same IP as server
const realDevices = clients.filter(client => {
    const clientIP = client.dynamicData?.clientIP;
    const isSameIP = clientIP === SERVER_IP;
    
    if (isSameIP) {
        console.log(`❌ Removing fake device: ${client.clientID} (IP: ${clientIP})`);
    }
    
    return !isSameIP;
});

console.log(`✅ Real devices kept: ${realDevices.length}`);
console.log(`🗑️  Fake devices removed: ${clients.length - realDevices.length}`);

// Update database with only real devices
db.set('clients', realDevices).write();

// Clear logs too
db.set('admin.logs', []).write();

console.log('🎉 Database cleaned!');
console.log('🌐 Refresh your browser to see only real devices');

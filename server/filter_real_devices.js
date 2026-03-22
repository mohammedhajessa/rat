const low = require('lowdb');
const FileSync = require('lowdb/adapters/FileSync');

// Load database
const adapter = new FileSync('maindb.json');
const db = low(adapter);

// Get all clients
const clients = db.get('clients').value();

// Filter out fake devices (devices that disconnect immediately)
const realDevices = clients.filter(client => {
    // Check if device has been online for more than 5 seconds
    const connectionTime = new Date(client.firstSeen).getTime();
    const lastSeenTime = new Date(client.lastSeen).getTime();
    const connectionDuration = lastSeenTime - connectionTime;
    
    // Keep devices that were connected for more than 5 seconds
    return connectionDuration > 5000;
});

// Update database with only real devices
db.set('clients', realDevices).write();

console.log(`✅ Filtered devices: ${clients.length} -> ${realDevices.length}`);
console.log(`📱 Removed ${clients.length - realDevices.length} fake devices`);
console.log(`🔧 Kept ${realDevices.length} real devices`);

// Show remaining devices
if (realDevices.length > 0) {
    console.log('\n📋 Real devices:');
    realDevices.forEach((device, index) => {
        console.log(`${index + 1}. ${device.clientID} (${device.isOnline ? 'Online' : 'Offline'})`);
    });
} else {
    console.log('\n📱 No real devices found');
}

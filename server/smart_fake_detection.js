const low = require('lowdb');
const FileSync = require('lowdb/adapters/FileSync');

// Load database
const adapter = new FileSync('maindb.json');
const db = low(adapter);

console.log('🧠 Smart fake device detection...');

// Get all clients
const clients = db.get('clients').value();
console.log(`📱 Total devices found: ${clients.length}`);

// Smart filtering based on connection patterns
const realDevices = clients.filter(client => {
    const connectionTime = new Date(client.firstSeen).getTime();
    const lastSeenTime = new Date(client.lastSeen).getTime();
    const connectionDuration = lastSeenTime - connectionTime;
    
    // Check for fake device patterns:
    // 1. Very short connection duration (less than 2 seconds)
    // 2. No device information (model, manufacturer, version all "Unknown")
    // 3. Same IP as server (but we'll allow this for legitimate devices)
    
    const isVeryShortConnection = connectionDuration < 2000; // Less than 2 seconds
    const hasNoDeviceInfo = client.dynamicData?.device?.model === 'Unknown' && 
                           client.dynamicData?.device?.manufacture === 'Unknown' && 
                           client.dynamicData?.device?.version === 'Unknown';
    
    // Only remove if it's a very short connection AND has no device info
    const isFake = isVeryShortConnection && hasNoDeviceInfo;
    
    if (isFake) {
        console.log(`❌ Removing fake device: ${client.clientID} (Duration: ${connectionDuration}ms, No device info)`);
    } else {
        console.log(`✅ Keeping device: ${client.clientID} (Duration: ${connectionDuration}ms)`);
    }
    
    return !isFake;
});

console.log(`✅ Real devices kept: ${realDevices.length}`);
console.log(`🗑️  Fake devices removed: ${clients.length - realDevices.length}`);

// Update database with only real devices
db.set('clients', realDevices).write();

// Clear logs too
db.set('admin.logs', []).write();

console.log('🎉 Smart filtering complete!');
console.log('🌐 Refresh your browser to see only real devices');

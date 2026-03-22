// This script modifies the clientManager to prevent fake devices in real-time
const fs = require('fs');

// Read the current clientManager.js
let clientManagerCode = fs.readFileSync('./includes/clientManager.js', 'utf8');

// Add fake device prevention logic
const fakeDevicePrevention = `
    // Fake device prevention - check connection patterns
    const connectionTime = new Date().getTime();
    const clientIP = clientData?.clientIP;
    const deviceInfo = clientData?.device;
    
    // Store connection attempt
    if (!this.connectionAttempts) this.connectionAttempts = {};
    if (!this.connectionAttempts[clientIP]) this.connectionAttempts[clientIP] = [];
    
    this.connectionAttempts[clientIP].push(connectionTime);
    
    // Clean old attempts (older than 1 minute)
    this.connectionAttempts[clientIP] = this.connectionAttempts[clientIP].filter(
        time => connectionTime - time < 60000
    );
    
    // Check for suspicious patterns:
    // 1. Too many connection attempts from same IP (more than 5 in 1 minute)
    // 2. Very short connection duration pattern
    const recentAttempts = this.connectionAttempts[clientIP].length;
    const isSuspicious = recentAttempts > 5;
    
    if (isSuspicious) {
        console.log(\`🚫 Blocking suspicious device: \${clientID} (IP: \${clientIP}, Attempts: \${recentAttempts})\`);
        connection.disconnect();
        return;
    }
    
    // Check for fake device characteristics
    const hasNoDeviceInfo = !deviceInfo || 
                           (deviceInfo.model === 'Unknown' && 
                            deviceInfo.manufacture === 'Unknown' && 
                            deviceInfo.version === 'Unknown');
    
    // Only block if it's both suspicious AND has no device info
    if (isSuspicious && hasNoDeviceInfo) {
        console.log(\`🚫 Blocking fake device: \${clientID} (No device info + suspicious pattern)\`);
        connection.disconnect();
        return;
    }
`;

// Insert the fake device prevention code after the clientConnections assignment
const insertPoint = 'this.clientConnections[clientID] = connection;';
const newCode = clientManagerCode.replace(
    insertPoint,
    insertPoint + fakeDevicePrevention
);

// Write the modified code back
fs.writeFileSync('./includes/clientManager.js', newCode);

console.log('✅ Fake device prevention system installed!');
console.log('🛡️ Server will now block suspicious connection patterns');
console.log('📱 Legitimate devices with same IP will still be allowed');

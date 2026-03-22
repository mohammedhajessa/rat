const fs = require('fs');
const path = require('path');
const low = require('lowdb');
const FileSync = require('lowdb/adapters/FileSync');

// Clear the main database
const adapter = new FileSync('maindb.json');
const db = low(adapter);

// Clear all devices and keep only admin
db.set('clients', []).write();
db.set('admin.logs', []).write();

console.log('✅ Database cleared successfully!');
console.log('📱 All devices removed from panel');
console.log('🔧 Admin account preserved');

// Clear all clientData JSON files
const clientDataDir = path.join(__dirname, 'clientData');
if (fs.existsSync(clientDataDir)) {
    const files = fs.readdirSync(clientDataDir);
    files.forEach(file => {
        if (file.endsWith('.json')) {
            const clientFilePath = path.join(clientDataDir, file);
            try {
                fs.writeFileSync(clientFilePath, JSON.stringify({}));
                console.log(`🗑️ Cleared: ${file}`);
            } catch (err) {
                console.error(`❌ Failed to clear ${file}:`, err);
            }
        }
    });
    if (files.filter(file => file.endsWith('.json')).length === 0) {
        console.log('ℹ️ No clientData JSON files found.');
    }
} else {
    console.log('ℹ️ clientData folder does not exist.');
}

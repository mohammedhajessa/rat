const low = require('lowdb');
const FileSync = require('lowdb/adapters/FileSync');

// Load database
const adapter = new FileSync('maindb.json');
const db = low(adapter);

// Clear all logs
db.set('admin.logs', []).write();

// Clear all clients
db.set('clients', []).write();

console.log('✅ All logs and devices cleared');
console.log('📱 Database is now completely clean');
console.log('🌐 Refresh your browser to see empty panel');

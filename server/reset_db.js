const fs = require('fs');
const path = require('path');

// Create a completely fresh database
const freshDb = {
  "admin": {
    "username": "admin",
    "password": "5f4dcc3b5aa765d61d8327deb882cf99",
    "loginToken": "1d400a2afcd942e5d329a42de7bc289e",
    "logs": []
  },
  "clients": []
};

// Write the fresh database
fs.writeFileSync('maindb.json', JSON.stringify(freshDb, null, 2));

console.log('✅ Database completely reset!');
console.log('📱 All devices and logs cleared');
console.log('🔧 Fresh admin account ready');
console.log('🌐 Refresh your browser now');

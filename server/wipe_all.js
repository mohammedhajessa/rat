const fs = require('fs');

// Create a completely empty database
const emptyDb = {
  "admin": {
    "username": "admin",
    "password": "5f4dcc3b5aa765d61d8327deb882cf99",
    "loginToken": "1d400a2afcd942e5d329a42de7bc289e",
    "logs": []
  },
  "clients": []
};

// Write the empty database
fs.writeFileSync('maindb.json', JSON.stringify(emptyDb, null, 2));

console.log('✅ Database completely wiped!');
console.log('📱 All devices and logs removed');
console.log('🔧 Only admin account remains');
console.log('🌐 Refresh your browser now');

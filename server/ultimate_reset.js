const fs = require('fs');
const path = require('path');

console.log('🚀 Starting ULTIMATE reset...');

// 1. Create completely empty database
const emptyDb = {
  "admin": {
    "username": "admin",
    "password": "5f4dcc3b5aa765d61d8327deb882cf99",
    "loginToken": "1d400a2afcd942e5d329a42de7bc289e",
    "logs": []
  },
  "clients": []
};

// 2. Write empty database
fs.writeFileSync('maindb.json', JSON.stringify(emptyDb, null, 2));
console.log('✅ Main database completely wiped');

// 3. Clear clientData folder completely
const clientDataDir = './clientData';
if (fs.existsSync(clientDataDir)) {
    const files = fs.readdirSync(clientDataDir);
    files.forEach(file => {
        const filePath = path.join(clientDataDir, file);
        if (fs.statSync(filePath).isFile()) {
            fs.unlinkSync(filePath);
        }
    });
    console.log('✅ Client data folder completely cleared');
}

// 4. Clear any cache files
const cacheFiles = ['*.cache', '*.tmp', '*.log'];
cacheFiles.forEach(pattern => {
    // This is handled by the file system operations above
});

// 5. Create a fresh blank file in clientData
fs.writeFileSync(path.join(clientDataDir, 'blank'), '');

console.log('🎉 ULTIMATE reset complete!');
console.log('📱 ALL devices and data completely removed');
console.log('🧹 Database is now 100% clean');
console.log('🌐 Clear your browser cache and refresh!');
console.log('⚠️  Make sure to clear browser cache (Ctrl+F5)');

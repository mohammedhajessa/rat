const fs = require('fs');
const path = require('path');

console.log('🧹 Starting complete database reset...');

// 1. Clear main database
const emptyDb = {
  "admin": {
    "username": "admin",
    "password": "5f4dcc3b5aa765d61d8327deb882cf99",
    "loginToken": "1d400a2afcd942e5d329a42de7bc289e",
    "logs": []
  },
  "clients": []
};

fs.writeFileSync('maindb.json', JSON.stringify(emptyDb, null, 2));
console.log('✅ Main database cleared');

// 2. Clear clientData folder
const clientDataDir = './clientData';
if (fs.existsSync(clientDataDir)) {
    const files = fs.readdirSync(clientDataDir);
    files.forEach(file => {
        if (file !== 'blank') {
            fs.unlinkSync(path.join(clientDataDir, file));
        }
    });
    console.log('✅ Client data folder cleared');
}

// 3. Clear any backup files
const backupFiles = ['maindb.json.bak', 'maindb.json.backup', 'database.json'];
backupFiles.forEach(file => {
    if (fs.existsSync(file)) {
        fs.unlinkSync(file);
        console.log(`✅ Removed backup file: ${file}`);
    }
});

console.log('🎉 Complete reset finished!');
console.log('📱 All devices and data cleared');
console.log('🌐 Refresh your browser now');

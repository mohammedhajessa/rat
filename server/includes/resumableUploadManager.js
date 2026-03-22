/**
 * ResumableUploadManager
 * 
 * A production-safe, memory-efficient resumable file upload service over Socket.IO.
 * 
 * Features:
 *   - Chunk-level acknowledgment (ACK) for each received chunk
 *   - Resume from last successful chunk after reconnect
 *   - Duplicate chunk prevention (idempotent writes)
 *   - Persistent upload state on disk (survives server restarts)
 *   - Streaming writes (each chunk appended directly to disk, never held in memory)
 *   - Automatic directory creation for target files
 *   - Stale upload cleanup (configurable TTL)
 * 
 * Protocol:
 *   1. Client → Server:  "upload_init"    { fileId, fileName, filePath, fileSize, totalChunks, chunkSize, fileExtension, fileType }
 *      Server → Client:  "upload_ready"   { fileId, resumeFromChunk }
 * 
 *   2. Client → Server:  "upload_chunk"   { fileId, chunkIndex, totalChunks, chunkSize, buffer (base64) }
 *      Server → Client:  "upload_ack"     { fileId, chunkIndex, status: "ok"|"duplicate"|"error" }
 * 
 *   3. Client → Server:  "upload_resume"  { fileId }
 *      Server → Client:  "upload_ready"   { fileId, resumeFromChunk, totalChunks, receivedChunks }
 * 
 *   4. Server → Client:  "upload_complete" { fileId, savedPath, fileSize, downloadTime, speed }
 *      (emitted automatically when all chunks are received)
 * 
 *   5. Client → Server:  "upload_cancel"  { fileId }
 *      Server → Client:  "upload_cancelled" { fileId }
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

// ─── Configuration ───────────────────────────────────────────────────────────

const UPLOAD_STATE_DIR = path.join(__dirname, '../uploads_state');   // JSON state files
const UPLOAD_TEMP_DIR  = path.join(__dirname, '../uploads_temp');    // Partially received files
const STALE_UPLOAD_TTL = 24 * 60 * 60 * 1000; // 24 hours — auto-clean uploads older than this
const CLEANUP_INTERVAL = 60 * 60 * 1000;       // Run cleanup every hour

// ─── Helpers ─────────────────────────────────────────────────────────────────

/**
 * Ensure a directory exists, creating it recursively if needed.
 */
function ensureDir(dirPath) {
    if (!fs.existsSync(dirPath)) {
        fs.mkdirSync(dirPath, { recursive: true });
    }
}

/**
 * Generate a short, unique file key for saved files.
 */
function generateFileKey() {
    const hash = crypto.createHash('md5').update(Date.now() + '' + Math.random()).digest('hex');
    return hash.substr(0, 5) + '-' + hash.substr(5, 4) + '-' + hash.substr(9, 5);
}

/**
 * Format bytes into human-readable string.
 */
function formatSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(2) + ' MB';
    return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB';
}

// ─── ResumableUploadManager Class ────────────────────────────────────────────

class ResumableUploadManager {
    constructor(downloadsFullPath, downloadsFolder) {
        // Where completed files are saved (the final destination)
        this.downloadsFullPath = downloadsFullPath;
        this.downloadsFolder   = downloadsFolder;

        // In-memory cache of active uploads (fileId → state)
        // This is a mirror of the on-disk JSON; always kept in sync.
        this.uploads = {};

        // Ensure required directories exist
        ensureDir(UPLOAD_STATE_DIR);
        ensureDir(UPLOAD_TEMP_DIR);
        ensureDir(this.downloadsFullPath);

        // Load any persisted upload states (server restart recovery)
        this._loadPersistedStates();

        // Schedule periodic cleanup of stale uploads
        this._cleanupTimer = setInterval(() => this._cleanupStaleUploads(), CLEANUP_INTERVAL);

        console.log('[ResumableUpload] Initialized.');
        console.log('  State dir : ' + UPLOAD_STATE_DIR);
        console.log('  Temp dir  : ' + UPLOAD_TEMP_DIR);
        console.log('  Output dir: ' + this.downloadsFullPath);
        const pendingCount = Object.keys(this.uploads).length;
        if (pendingCount > 0) {
            console.log('  Recovered ' + pendingCount + ' pending upload(s) from disk.');
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Initialize a new upload or return resume info for an existing one.
     * 
     * @param {Object} params - { fileId, fileName, filePath, fileSize, totalChunks, chunkSize, fileExtension, fileType, clientID }
     * @returns {Object} { fileId, resumeFromChunk, totalChunks, isResume }
     */
    initUpload(params) {
        const { fileId, fileName, filePath, fileSize, totalChunks, chunkSize, fileExtension, fileType, clientID } = params;

        // If upload already exists (client reconnected), return resume info
        if (this.uploads[fileId]) {
            const state = this.uploads[fileId];
            const resumeFrom = this._getNextExpectedChunk(state);
            console.log(`[ResumableUpload] Resume existing upload: ${fileId} (${fileName}) from chunk ${resumeFrom}/${totalChunks}`);
            
            // Update timestamp so it doesn't get cleaned up
            state.lastActivity = Date.now();
            this._persistState(fileId);

            return {
                fileId,
                resumeFromChunk: resumeFrom,
                totalChunks: state.totalChunks,
                receivedChunks: state.receivedChunks.length,
                isResume: true
            };
        }

        // Generate a unique key for the final saved file
        const fileKey = generateFileKey();
        const ext = fileExtension ? ('.' + fileExtension) : 
                    (fileName.lastIndexOf('.') !== -1 ? fileName.substring(fileName.lastIndexOf('.')) : '.unknown');
        const savedFileName = fileKey + ext;
        const savedFilePath = path.join(this.downloadsFullPath, savedFileName);
        const tempFilePath  = path.join(UPLOAD_TEMP_DIR, fileId + '.part');

        // Create new upload state
        const state = {
            fileId,
            clientID,
            fileName,
            sourcePath: filePath,
            fileSize,
            totalChunks,
            chunkSize,
            fileExtension: ext,
            fileType: fileType || 'Unknown',
            fileKey,
            savedFileName,
            savedFilePath,
            tempFilePath,
            receivedChunks: [],       // Array of received chunk indices (sorted)
            totalBytesReceived: 0,
            startTime: Date.now(),
            lastActivity: Date.now(),
            status: 'in_progress'
        };

        this.uploads[fileId] = state;
        this._persistState(fileId);

        // Create/truncate the temp file
        fs.writeFileSync(tempFilePath, Buffer.alloc(0));

        console.log(`[ResumableUpload] New upload initialized:`);
        console.log(`  fileId     : ${fileId}`);
        console.log(`  fileName   : ${fileName}`);
        console.log(`  fileSize   : ${formatSize(fileSize)}`);
        console.log(`  totalChunks: ${totalChunks}`);
        console.log(`  chunkSize  : ${formatSize(chunkSize)}`);
        console.log(`  tempFile   : ${tempFilePath}`);

        return {
            fileId,
            resumeFromChunk: 0,
            totalChunks,
            receivedChunks: 0,
            isResume: false
        };
    }

    /**
     * Process a received chunk.
     * Writes the chunk data to the correct position in the temp file.
     * Returns ACK info.
     * 
     * @param {Object} params - { fileId, chunkIndex, totalChunks, chunkSize, buffer (base64 string) }
     * @returns {Object} { fileId, chunkIndex, status, isComplete, savedInfo? }
     */
    receiveChunk(params) {
        const { fileId, chunkIndex, buffer } = params;
        const state = this.uploads[fileId];

        // Validate upload exists
        if (!state) {
            console.error(`[ResumableUpload] Unknown fileId: ${fileId}`);
            return { fileId, chunkIndex, status: 'error', error: 'Unknown upload. Send upload_init first.' };
        }

        // Check for duplicate chunk (idempotent — safe to re-receive)
        if (state.receivedChunks.includes(chunkIndex)) {
            console.log(`[ResumableUpload] Duplicate chunk ${chunkIndex} for ${fileId}, ignoring.`);
            return { 
                fileId, 
                chunkIndex, 
                status: 'duplicate', 
                isComplete: state.receivedChunks.length === state.totalChunks 
            };
        }

        // Decode base64 buffer to binary
        const chunkBuffer = Buffer.from(buffer, 'base64');
        const chunkByteSize = chunkBuffer.length;

        // Write chunk at the correct byte offset in the temp file
        // offset = chunkIndex * chunkSize (except possibly the last chunk)
        const byteOffset = chunkIndex * state.chunkSize;

        try {
            const fd = fs.openSync(state.tempFilePath, 'r+');
            fs.writeSync(fd, chunkBuffer, 0, chunkByteSize, byteOffset);
            fs.closeSync(fd);
        } catch (err) {
            // If file doesn't exist (edge case), create it and retry
            if (err.code === 'ENOENT') {
                ensureDir(path.dirname(state.tempFilePath));
                fs.writeFileSync(state.tempFilePath, Buffer.alloc(0));
                const fd = fs.openSync(state.tempFilePath, 'r+');
                fs.writeSync(fd, chunkBuffer, 0, chunkByteSize, byteOffset);
                fs.closeSync(fd);
            } else {
                console.error(`[ResumableUpload] Error writing chunk ${chunkIndex} for ${fileId}:`, err.message);
                return { fileId, chunkIndex, status: 'error', error: err.message };
            }
        }

        // Update state
        state.receivedChunks.push(chunkIndex);
        state.receivedChunks.sort((a, b) => a - b); // Keep sorted
        state.totalBytesReceived += chunkByteSize;
        state.lastActivity = Date.now();

        // Persist state every chunk (for crash recovery)
        // For very high throughput, you could persist every N chunks instead
        this._persistState(fileId);

        // Log progress periodically
        const received = state.receivedChunks.length;
        const total = state.totalChunks;
        if (received % 5 === 0 || received === total) {
            const pct = ((received / total) * 100).toFixed(1);
            console.log(`[ResumableUpload] ${fileId} chunk ${chunkIndex} OK — ${received}/${total} (${pct}%) — ${formatSize(state.totalBytesReceived)}`);
        }

        // Check if upload is complete
        if (received === total) {
            return this._finalizeUpload(fileId);
        }

        return { fileId, chunkIndex, status: 'ok', isComplete: false };
    }

    /**
     * Get resume information for a fileId.
     * Called when client reconnects and wants to know where to continue.
     * 
     * @param {string} fileId
     * @returns {Object|null} Resume info or null if upload not found
     */
    getResumeInfo(fileId) {
        const state = this.uploads[fileId];
        if (!state) return null;

        const resumeFrom = this._getNextExpectedChunk(state);
        return {
            fileId,
            resumeFromChunk: resumeFrom,
            totalChunks: state.totalChunks,
            receivedChunks: state.receivedChunks.length,
            totalBytesReceived: state.totalBytesReceived,
            fileName: state.fileName,
            fileSize: state.fileSize
        };
    }

    /**
     * Get all pending uploads for a specific client.
     * Useful when client reconnects to resume all in-progress uploads.
     * 
     * @param {string} clientID
     * @returns {Array} Array of resume info objects
     */
    getPendingUploadsForClient(clientID) {
        const pending = [];
        for (const fileId of Object.keys(this.uploads)) {
            const state = this.uploads[fileId];
            if (state.clientID === clientID && state.status === 'in_progress') {
                pending.push(this.getResumeInfo(fileId));
            }
        }
        return pending;
    }

    /**
     * Cancel and clean up an upload.
     * 
     * @param {string} fileId
     * @returns {boolean} true if cancelled, false if not found
     */
    cancelUpload(fileId) {
        const state = this.uploads[fileId];
        if (!state) return false;

        console.log(`[ResumableUpload] Cancelling upload: ${fileId} (${state.fileName})`);

        // Delete temp file
        this._safeDelete(state.tempFilePath);

        // Delete state file
        this._safeDelete(path.join(UPLOAD_STATE_DIR, fileId + '.json'));

        // Remove from memory
        delete this.uploads[fileId];

        return true;
    }

    // ── Private Methods ──────────────────────────────────────────────────────

    /**
     * Determine the next chunk index the server expects.
     * Finds the first gap in received chunks, or totalChunks if all received.
     */
    _getNextExpectedChunk(state) {
        if (state.receivedChunks.length === 0) return 0;
        
        // Find the first missing index
        for (let i = 0; i < state.totalChunks; i++) {
            if (!state.receivedChunks.includes(i)) {
                return i;
            }
        }
        return state.totalChunks; // All received
    }

    /**
     * Finalize a completed upload: move temp file to final destination, clean up state.
     */
    _finalizeUpload(fileId) {
        const state = this.uploads[fileId];
        const downloadTime = (Date.now() - state.startTime) / 1000;
        const speed = (state.fileSize / 1024 / 1024) / downloadTime;

        console.log(`[ResumableUpload] ✅ Upload complete: ${state.fileName}`);
        console.log(`  Size : ${formatSize(state.fileSize)}`);
        console.log(`  Time : ${downloadTime.toFixed(1)}s`);
        console.log(`  Speed: ${speed.toFixed(2)} MB/s`);

        // Ensure output directory exists
        ensureDir(path.dirname(state.savedFilePath));

        try {
            // Truncate the temp file to the exact file size (last chunk may have been padded)
            fs.truncateSync(state.tempFilePath, state.fileSize);

            // Move temp file to final destination
            fs.renameSync(state.tempFilePath, state.savedFilePath);
        } catch (err) {
            // If rename fails (cross-device), fall back to copy+delete
            if (err.code === 'EXDEV') {
                fs.copyFileSync(state.tempFilePath, state.savedFilePath);
                this._safeDelete(state.tempFilePath);
            } else {
                console.error(`[ResumableUpload] Error finalizing ${fileId}:`, err.message);
                return { 
                    fileId, 
                    chunkIndex: state.totalChunks - 1, 
                    status: 'error', 
                    error: 'Failed to save file: ' + err.message,
                    isComplete: false 
                };
            }
        }

        // Build result info for the caller (to save to DB, broadcast, etc.)
        const savedInfo = {
            fileId,
            time: new Date(),
            type: 'fileDownload',
            originalName: state.fileName,
            path: state.downloadsFolder + '/' + state.savedFileName,
            savedFilePath: state.savedFilePath,
            size: state.fileSize,
            format: state.fileType,
            status: 'File uploaded successfully (resumable)',
            sourcePath: state.sourcePath,
            downloadTime: downloadTime.toFixed(2) + 's',
            speed: speed.toFixed(2) + ' MB/s',
            totalChunks: state.totalChunks,
            clientID: state.clientID
        };

        // Clean up state
        state.status = 'completed';
        this._safeDelete(path.join(UPLOAD_STATE_DIR, fileId + '.json'));
        delete this.uploads[fileId];

        return { 
            fileId, 
            chunkIndex: state.totalChunks - 1, 
            status: 'ok', 
            isComplete: true, 
            savedInfo 
        };
    }

    /**
     * Persist upload state to a JSON file on disk.
     */
    _persistState(fileId) {
        const state = this.uploads[fileId];
        if (!state) return;

        const stateFilePath = path.join(UPLOAD_STATE_DIR, fileId + '.json');
        
        // Write a minimal version of state (exclude large data)
        const persistable = {
            fileId: state.fileId,
            clientID: state.clientID,
            fileName: state.fileName,
            sourcePath: state.sourcePath,
            fileSize: state.fileSize,
            totalChunks: state.totalChunks,
            chunkSize: state.chunkSize,
            fileExtension: state.fileExtension,
            fileType: state.fileType,
            fileKey: state.fileKey,
            savedFileName: state.savedFileName,
            savedFilePath: state.savedFilePath,
            tempFilePath: state.tempFilePath,
            receivedChunks: state.receivedChunks,
            totalBytesReceived: state.totalBytesReceived,
            startTime: state.startTime,
            lastActivity: state.lastActivity,
            status: state.status
        };

        try {
            fs.writeFileSync(stateFilePath, JSON.stringify(persistable, null, 2));
        } catch (err) {
            console.error(`[ResumableUpload] Failed to persist state for ${fileId}:`, err.message);
        }
    }

    /**
     * Load all persisted upload states from disk (called on server start).
     */
    _loadPersistedStates() {
        try {
            if (!fs.existsSync(UPLOAD_STATE_DIR)) return;

            const files = fs.readdirSync(UPLOAD_STATE_DIR);
            for (const file of files) {
                if (!file.endsWith('.json')) continue;
                try {
                    const data = fs.readFileSync(path.join(UPLOAD_STATE_DIR, file), 'utf8');
                    const state = JSON.parse(data);
                    
                    // Only load in-progress uploads
                    if (state.status === 'in_progress' && state.fileId) {
                        // Verify temp file still exists
                        if (fs.existsSync(state.tempFilePath)) {
                            this.uploads[state.fileId] = state;
                        } else {
                            // Temp file is gone — clean up the state
                            console.log(`[ResumableUpload] Temp file missing for ${state.fileId}, cleaning up.`);
                            this._safeDelete(path.join(UPLOAD_STATE_DIR, file));
                        }
                    }
                } catch (parseErr) {
                    console.error(`[ResumableUpload] Failed to load state file ${file}:`, parseErr.message);
                }
            }
        } catch (err) {
            console.error('[ResumableUpload] Failed to load persisted states:', err.message);
        }
    }

    /**
     * Clean up uploads that have been idle for longer than STALE_UPLOAD_TTL.
     */
    _cleanupStaleUploads() {
        const now = Date.now();
        let cleaned = 0;

        for (const fileId of Object.keys(this.uploads)) {
            const state = this.uploads[fileId];
            if (now - state.lastActivity > STALE_UPLOAD_TTL) {
                console.log(`[ResumableUpload] Cleaning stale upload: ${fileId} (${state.fileName}), idle for ${((now - state.lastActivity) / 3600000).toFixed(1)}h`);
                this.cancelUpload(fileId);
                cleaned++;
            }
        }

        if (cleaned > 0) {
            console.log(`[ResumableUpload] Cleaned ${cleaned} stale upload(s).`);
        }
    }

    /**
     * Safely delete a file (no-throw).
     */
    _safeDelete(filePath) {
        try {
            if (fs.existsSync(filePath)) {
                fs.unlinkSync(filePath);
            }
        } catch (err) {
            console.error(`[ResumableUpload] Failed to delete ${filePath}:`, err.message);
        }
    }

    /**
     * Destroy the manager (clear timers).
     */
    destroy() {
        if (this._cleanupTimer) {
            clearInterval(this._cleanupTimer);
            this._cleanupTimer = null;
        }
    }
}

module.exports = ResumableUploadManager;




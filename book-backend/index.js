const express = require('express');
const cors = require('cors');
const multer = require('multer');
const path = require('path');
const fs = require('fs');
const { v4: uuidv4 } = require('uuid');

// Initialize Express
const app = express();
const PORT = process.env.PORT || 5000;

// Middleware
app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// Serve static files (book covers, PDFs, etc.)
app.use('/uploads', express.static(path.join(__dirname, 'uploads')));

// Ensure uploads directories exist (for VPS)
const uploadsDir = path.join(__dirname, 'uploads');
const coversDir = path.join(uploadsDir, 'covers');
const booksDir = path.join(uploadsDir, 'books');

// Ensuring upload directories on disk - VPS friendly
[uploadsDir, coversDir, booksDir].forEach(dir => {
    if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
    }
});

// Simple JSON database, using absolute path for VPS safety
const dbPath = path.join(__dirname, 'database.json');
let database = { books: [], categories: [] };

// Load database
function loadDatabase() {
    try {
        if (fs.existsSync(dbPath)) {
            const data = fs.readFileSync(dbPath, 'utf8');
            database = JSON.parse(data);
            if (!database.books) database.books = [];
            if (!database.categories) database.categories = [];

            // Migrate: merge Arabic fields into primary fields
            let migrated = false;
            database.books.forEach(book => {
                if (book.titleAr && book.titleAr !== book.title) {
                    book.title = book.titleAr;
                    migrated = true;
                }
                if (book.authorAr && book.authorAr !== book.author) {
                    book.author = book.authorAr;
                    migrated = true;
                }
                if (book.descriptionAr && book.descriptionAr !== book.description) {
                    book.description = book.descriptionAr;
                    migrated = true;
                }
                // Remove old Arabic-specific fields
                delete book.titleAr;
                delete book.authorAr;
                delete book.descriptionAr;
            });

            database.categories.forEach(cat => {
                if (cat.nameAr && cat.nameAr !== cat.name) {
                    cat.name = cat.nameAr;
                    migrated = true;
                }
                delete cat.nameAr;
            });

            if (migrated) saveDatabase();
        } else {
            // Initialize with default categories (Arabic)
            database = {
                books: [],
                categories: [
                    { id: '1', name: 'روايات' },
                    { id: '2', name: 'غير روائي' },
                    { id: '3', name: 'علوم' },
                    { id: '4', name: 'تاريخ' },
                    { id: '5', name: 'دين' },
                    { id: '6', name: 'أطفال' },
                    { id: '7', name: 'شعر' },
                    { id: '8', name: 'فلسفة' },
                    { id: '9', name: 'تطوير ذاتي' },
                    { id: '10', name: 'تكنولوجيا' }
                ]
            };
            saveDatabase();
        }
    } catch (error) {
        console.error('Error loading database:', error);
    }
}

// Save database (VPS: robust write)
function saveDatabase() {
    try {
        fs.writeFileSync(dbPath, JSON.stringify(database, null, 2));
    } catch (error) {
        console.error('Error saving database:', error);
    }
}

// Get category name by ID
function getCategoryName(categoryId) {
    const cat = database.categories.find(c => c.id === categoryId);
    return cat ? cat.name : 'غير مصنف';
}

// Enrich book with category name and file size info
function enrichBook(book) {
    const enriched = { ...book };
    enriched.categoryName = getCategoryName(book.categoryId);

    // Add file size info if book file exists (VPS: check absolute path)
    if (book.bookFileUrl) {
        try {
            const filePath = path.join(__dirname, book.bookFileUrl);
            if (fs.existsSync(filePath)) {
                const stats = fs.statSync(filePath);
                enriched.fileSize = stats.size;
                enriched.fileSizeMB = (stats.size / (1024 * 1024)).toFixed(2);
            }
        } catch (e) { /* ignore */ }
    }

    return enriched;
}

// Initialize database
loadDatabase();

// Configure multer for file uploads (VPS friendly)
const storage = multer.diskStorage({
    destination: (req, file, cb) => {
        if (file.fieldname === 'cover') {
            cb(null, coversDir);
        } else if (file.fieldname === 'bookFile') {
            cb(null, booksDir);
        } else {
            cb(null, uploadsDir);
        }
    },
    filename: (req, file, cb) => {
        const ext = path.extname(file.originalname);
        const filename = `${uuidv4()}${ext}`;
        cb(null, filename);
    }
});

const upload = multer({
    storage,
    limits: { fileSize: 100 * 1024 * 1024 }, // 100MB limit
    fileFilter: (req, file, cb) => {
        if (file.fieldname === 'cover') {
            if (file.mimetype.startsWith('image/')) {
                cb(null, true);
            } else {
                cb(new Error('الغلاف يجب أن يكون صورة'), false);
            }
        } else if (file.fieldname === 'bookFile') {
            const allowedTypes = ['application/pdf', 'application/epub+zip', 'text/plain'];
            if (allowedTypes.includes(file.mimetype) || file.originalname.endsWith('.pdf') || file.originalname.endsWith('.epub')) {
                cb(null, true);
            } else {
                cb(new Error('يجب أن يكون الملف PDF أو EPUB أو TXT'), false);
            }
        } else {
            cb(null, true);
        }
    }
});

// ============ API ROUTES ============

// Root route - returns nothing (hide server info)
app.get('/', (req, res) => {
    res.status(404).end();
});

// Health check
app.get('/api/health', (req, res) => {
    res.json({ success: true, status: 'ok', timestamp: new Date().toISOString() });
});

// Dashboard stats
app.get('/api/stats', (req, res) => {
    const totalBooks = database.books.length;
    const totalCategories = database.categories.length;
    const totalViews = database.books.reduce((sum, b) => sum + (b.views || 0), 0);
    const totalDownloads = database.books.reduce((sum, b) => sum + (b.downloads || 0), 0);
    const featuredCount = database.books.filter(b => b.featured).length;
    const withFiles = database.books.filter(b => b.bookFileUrl).length;
    const withCovers = database.books.filter(b => b.coverUrl).length;

    const categoryStats = database.categories.map(cat => ({
        ...cat,
        bookCount: database.books.filter(b => b.categoryId === cat.id).length
    }));

    const recentBooks = [...database.books]
        .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
        .slice(0, 5)
        .map(enrichBook);

    const topRated = [...database.books]
        .filter(b => b.ratingCount > 0)
        .sort((a, b) => b.rating - a.rating)
        .slice(0, 5)
        .map(enrichBook);

    res.json({
        success: true,
        stats: {
            totalBooks, totalCategories, totalViews, totalDownloads,
            featuredCount, withFiles, withCovers,
            categoryStats, recentBooks, topRated
        }
    });
});

// ============ BOOKS API ============

// Get all books
app.get('/api/books', (req, res) => {
    const { category, featured, search, limit, sort, page, perPage } = req.query;

    let books = [...database.books];

    // Filter by category (by ID or name)
    if (category) {
        books = books.filter(b =>
            b.categoryId === category ||
            getCategoryName(b.categoryId) === category
        );
    }

    // Filter featured
    if (featured === 'true') {
        books = books.filter(b => b.featured === true);
    }

    // Search
    if (search) {
        const searchLower = search.toLowerCase();
        books = books.filter(b =>
            (b.title && b.title.toLowerCase().includes(searchLower)) ||
            (b.author && b.author.toLowerCase().includes(searchLower)) ||
            (b.description && b.description.toLowerCase().includes(searchLower))
        );
    }

    // Sort
    switch (sort) {
        case 'title':
            books.sort((a, b) => (a.title || '').localeCompare(b.title || ''));
            break;
        case 'rating':
            books.sort((a, b) => (b.rating || 0) - (a.rating || 0));
            break;
        case 'views':
            books.sort((a, b) => (b.views || 0) - (a.views || 0));
            break;
        case 'downloads':
            books.sort((a, b) => (b.downloads || 0) - (a.downloads || 0));
            break;
        case 'oldest':
            books.sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
            break;
        case 'newest':
        default:
            books.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
            break;
    }

    // Pagination
    const totalCount = books.length;
    const itemsPerPage = parseInt(perPage) || 20;
    const currentPage = parseInt(page) || 1;

    if (page) {
        const start = (currentPage - 1) * itemsPerPage;
        books = books.slice(start, start + itemsPerPage);
    } else if (limit) {
        books = books.slice(0, parseInt(limit));
    }

    const enrichedBooks = books.map(enrichBook);

    res.json({
        success: true,
        books: enrichedBooks,
        pagination: {
            total: totalCount,
            page: currentPage,
            perPage: itemsPerPage,
            totalPages: Math.ceil(totalCount / itemsPerPage)
        }
    });
});

// Search endpoint
app.get('/api/search', (req, res) => {
    const { q, category, limit } = req.query;

    if (!q || q.trim().length === 0) {
        return res.json({ success: true, books: [], query: '' });
    }

    const searchLower = q.toLowerCase().trim();
    let books = database.books.filter(b =>
        (b.title && b.title.toLowerCase().includes(searchLower)) ||
        (b.author && b.author.toLowerCase().includes(searchLower)) ||
        (b.description && b.description.toLowerCase().includes(searchLower))
    );

    if (category) {
        books = books.filter(b => b.categoryId === category);
    }

    // Sort by relevance (title match first)
    books.sort((a, b) => {
        const aTitle = (a.title?.toLowerCase().includes(searchLower)) ? 1 : 0;
        const bTitle = (b.title?.toLowerCase().includes(searchLower)) ? 1 : 0;
        return bTitle - aTitle;
    });

    if (limit) {
        books = books.slice(0, parseInt(limit));
    }

    res.json({
        success: true,
        books: books.map(enrichBook),
        query: q,
        totalResults: books.length
    });
});

// Get single book
app.get('/api/books/:id', (req, res) => {
    const book = database.books.find(b => b.id === req.params.id);

    if (!book) {
        return res.status(404).json({ success: false, error: 'الكتاب غير موجود' });
    }

    // Increment view count
    book.views = (book.views || 0) + 1;
    saveDatabase();

    res.json({ success: true, book: enrichBook(book) });
});

// Create new book
app.post('/api/books', upload.fields([
    { name: 'cover', maxCount: 1 },
    { name: 'bookFile', maxCount: 1 }
]), (req, res) => {
    try {
        const { title, author, description, categoryId, featured, pages, coverImageUrl } = req.body;

        if (!title || !author) {
            return res.status(400).json({ success: false, error: 'العنوان والمؤلف مطلوبان' });
        }

        // Cover: file upload or URL
        let coverUrl = null;
        if (req.files?.cover) {
            coverUrl = `/uploads/covers/${req.files.cover[0].filename}`;
        } else if (coverImageUrl && coverImageUrl.trim()) {
            coverUrl = coverImageUrl.trim();
        }

        const book = {
            id: uuidv4(),
            title,
            author,
            description: description || '',
            categoryId: categoryId || '1',
            featured: featured === 'true' || featured === true,
            pages: parseInt(pages) || 0,
            views: 0,
            downloads: 0,
            rating: 0,
            ratingCount: 0,
            coverUrl,
            bookFileUrl: req.files?.bookFile ? `/uploads/books/${req.files.bookFile[0].filename}` : null,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
        };

        database.books.push(book);
        saveDatabase();

        res.json({ success: true, book: enrichBook(book) });
    } catch (error) {
        console.error('Error creating book:', error);
        res.status(500).json({ success: false, error: error.message });
    }
});

// Update book
app.put('/api/books/:id', upload.fields([
    { name: 'cover', maxCount: 1 },
    { name: 'bookFile', maxCount: 1 }
]), (req, res) => {
    try {
        const bookIndex = database.books.findIndex(b => b.id === req.params.id);

        if (bookIndex === -1) {
            return res.status(404).json({ success: false, error: 'الكتاب غير موجود' });
        }

        const book = database.books[bookIndex];
        const { title, author, description, categoryId, featured, pages, coverImageUrl } = req.body;

        if (title !== undefined) book.title = title;
        if (author !== undefined) book.author = author;
        if (description !== undefined) book.description = description;
        if (categoryId !== undefined) book.categoryId = categoryId;
        if (featured !== undefined) book.featured = featured === 'true' || featured === true;
        if (pages !== undefined) book.pages = parseInt(pages);

        // Update cover
        if (req.files?.cover) {
            if (book.coverUrl && book.coverUrl.startsWith('/uploads/')) {
                const oldPath = path.join(__dirname, book.coverUrl);
                if (fs.existsSync(oldPath)) fs.unlinkSync(oldPath);
            }
            book.coverUrl = `/uploads/covers/${req.files.cover[0].filename}`;
        } else if (coverImageUrl !== undefined) {
            if (book.coverUrl && book.coverUrl.startsWith('/uploads/')) {
                const oldPath = path.join(__dirname, book.coverUrl);
                if (fs.existsSync(oldPath)) fs.unlinkSync(oldPath);
            }
            book.coverUrl = coverImageUrl.trim() || null;
        }

        if (req.files?.bookFile) {
            if (book.bookFileUrl) {
                const oldPath = path.join(__dirname, book.bookFileUrl);
                if (fs.existsSync(oldPath)) fs.unlinkSync(oldPath);
            }
            book.bookFileUrl = `/uploads/books/${req.files.bookFile[0].filename}`;
        }

        book.updatedAt = new Date().toISOString();
        saveDatabase();

        res.json({ success: true, book: enrichBook(book) });
    } catch (error) {
        console.error('Error updating book:', error);
        res.status(500).json({ success: false, error: error.message });
    }
});

// Delete book
app.delete('/api/books/:id', (req, res) => {
    try {
        const bookIndex = database.books.findIndex(b => b.id === req.params.id);

        if (bookIndex === -1) {
            return res.status(404).json({ success: false, error: 'الكتاب غير موجود' });
        }

        const book = database.books[bookIndex];

        if (book.coverUrl && book.coverUrl.startsWith('/uploads/')) {
            const coverPath = path.join(__dirname, book.coverUrl);
            if (fs.existsSync(coverPath)) fs.unlinkSync(coverPath);
        }
        if (book.bookFileUrl) {
            const bookPath = path.join(__dirname, book.bookFileUrl);
            if (fs.existsSync(bookPath)) fs.unlinkSync(bookPath);
        }

        database.books.splice(bookIndex, 1);
        saveDatabase();

        res.json({ success: true, message: 'تم حذف الكتاب' });
    } catch (error) {
        console.error('Error deleting book:', error);
        res.status(500).json({ success: false, error: error.message });
    }
});

// Track download
app.post('/api/books/:id/download', (req, res) => {
    const book = database.books.find(b => b.id === req.params.id);

    if (!book) {
        return res.status(404).json({ success: false, error: 'الكتاب غير موجود' });
    }

    book.downloads = (book.downloads || 0) + 1;
    saveDatabase();

    res.json({ success: true, downloads: book.downloads });
});

// Rate book
app.post('/api/books/:id/rate', (req, res) => {
    const { rating } = req.body;

    if (!rating || rating < 1 || rating > 5) {
        return res.status(400).json({ success: false, error: 'التقييم يجب أن يكون بين 1 و 5' });
    }

    const book = database.books.find(b => b.id === req.params.id);

    if (!book) {
        return res.status(404).json({ success: false, error: 'الكتاب غير موجود' });
    }

    const totalRating = (book.rating || 0) * (book.ratingCount || 0) + parseFloat(rating);
    book.ratingCount = (book.ratingCount || 0) + 1;
    book.rating = Math.round((totalRating / book.ratingCount) * 10) / 10;

    saveDatabase();

    res.json({ success: true, rating: book.rating, ratingCount: book.ratingCount });
});

// ============ CATEGORIES API ============

app.get('/api/categories', (req, res) => {
    const categories = database.categories.map(cat => ({
        ...cat,
        bookCount: database.books.filter(b => b.categoryId === cat.id).length
    }));

    res.json({ success: true, categories });
});

app.post('/api/categories', (req, res) => {
    const { name } = req.body;

    if (!name) {
        return res.status(400).json({ success: false, error: 'الاسم مطلوب' });
    }

    const category = { id: uuidv4(), name };

    database.categories.push(category);
    saveDatabase();

    res.json({ success: true, category });
});

app.put('/api/categories/:id', (req, res) => {
    const catIndex = database.categories.findIndex(c => c.id === req.params.id);

    if (catIndex === -1) {
        return res.status(404).json({ success: false, error: 'التصنيف غير موجود' });
    }

    const { name } = req.body;
    if (name !== undefined) database.categories[catIndex].name = name;

    saveDatabase();

    res.json({ success: true, category: database.categories[catIndex] });
});

app.delete('/api/categories/:id', (req, res) => {
    const categoryIndex = database.categories.findIndex(c => c.id === req.params.id);

    if (categoryIndex === -1) {
        return res.status(404).json({ success: false, error: 'التصنيف غير موجود' });
    }

    database.categories.splice(categoryIndex, 1);
    saveDatabase();

    res.json({ success: true, message: 'تم حذف التصنيف' });
});

// ============ ADMIN PANEL ============

// Serving the admin panel files (ensure 'admin' folder exists on VPS)
app.get('/admin', (req, res) => {
    res.sendFile(path.join(__dirname, 'admin', 'index.html'));
});

app.use('/admin', express.static(path.join(__dirname, 'admin')));

// ============ CATCH-ALL & ERROR HANDLING ============

// Any unknown route returns 404
app.use((req, res) => {
    res.status(404).end();
});

// Error handling middleware for multer
app.use((err, req, res, next) => {
    if (err instanceof multer.MulterError) {
        if (err.code === 'LIMIT_FILE_SIZE') {
            return res.status(400).json({ success: false, error: 'حجم الملف كبير جداً. الحد الأقصى 100MB' });
        }
        return res.status(400).json({ success: false, error: err.message });
    }
    if (err) {
        return res.status(500).json({ success: false, error: err.message });
    }
    next();
});

// ============ START SERVER ============

// Use '0.0.0.0' for VPS compatibility
app.listen(PORT, '0.0.0.0', () => {
    // Try to get VPS IP from env, fallback to local. You can set VPS_HOST in your env.
    const host = process.env.VPS_HOST || 'localhost';
    console.log(`📚 Book Backend API v3.0 running on port ${PORT}`);
    console.log(`   API: http://${host}:${PORT}/api`);
    console.log(`   Admin: http://${host}:${PORT}/admin`);
    console.log(`   Books: ${database.books.length}`);
    console.log(`   Categories: ${database.categories.length}`);
});

# نشر Book Backend على VPS (AWS)

**السيرفر:** `18.119.96.175`
**المنفذ:** `5000`
**SSH:** `ssh -i C:\Users\adrie\Downloads\rat.pem ubuntu@18.119.96.175`

---

## الخطوة 1: رفع الملفات إلى السيرفر

من PowerShell على جهازك المحلي:

```powershell
# رفع مجلد book-backend بالكامل (بدون node_modules)
scp -i C:\Users\adrie\Downloads\rat.pem -r C:\Users\adrie\Downloads\v1\h4` -` Copy\book-backend\index.js ubuntu@18.119.96.175:~/book-backend/
scp -i C:\Users\adrie\Downloads\rat.pem -r C:\Users\adrie\Downloads\v1\h4` -` Copy\book-backend\package.json ubuntu@18.119.96.175:~/book-backend/
scp -i C:\Users\adrie\Downloads\rat.pem -r C:\Users\adrie\Downloads\v1\h4` -` Copy\book-backend\database.json ubuntu@18.119.96.175:~/book-backend/
```

### رفع مجلد admin:
```powershell
scp -i C:\Users\adrie\Downloads\rat.pem -r "C:\Users\adrie\Downloads\v1\h4 - Copy\book-backend\admin" ubuntu@18.119.96.175:~/book-backend/
```

### رفع مجلد uploads (الأغلفة وملفات الكتب):
```powershell
scp -i C:\Users\adrie\Downloads\rat.pem -r "C:\Users\adrie\Downloads\v1\h4 - Copy\book-backend\uploads" ubuntu@18.119.96.175:~/book-backend/
```

---

## الخطوة 2: الاتصال بالسيرفر

```powershell
ssh -i C:\Users\adrie\Downloads\rat.pem ubuntu@18.119.96.175
```

---

## الخطوة 3: تثبيت Node.js (إذا لم يكن مثبتاً)

```bash
# تحقق أولاً
node -v

# إذا غير مثبت:
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs
```

---

## الخطوة 4: تثبيت الحزم وتشغيل السيرفر

```bash
cd ~/book-backend
npm install
```

### تشغيل تجريبي:
```bash
node index.js
```

تأكد أنه يعمل → `📚 Book Backend API v3.0 running on port 5000`

اضغط `Ctrl+C` لإيقافه.

---

## الخطوة 5: تشغيل دائم مع PM2

```bash
# تثبيت PM2
sudo npm install -g pm2

# تشغيل السيرفر
pm2 start index.js --name book-backend

# التشغيل التلقائي عند إعادة تشغيل السيرفر
pm2 startup
pm2 save

# أوامر مفيدة:
pm2 status          # حالة السيرفر
pm2 logs book-backend   # عرض اللوغ
pm2 restart book-backend # إعادة تشغيل
pm2 stop book-backend    # إيقاف
```

---

## الخطوة 6: فتح المنفذ 5000 في AWS Security Group

1. ادخل على **AWS Console** → **EC2** → **Instances**
2. اختر الـ Instance → **Security** → اضغط على **Security Group**
3. **Edit inbound rules** → **Add rule**:
   - **Type:** Custom TCP
   - **Port:** 5000
   - **Source:** 0.0.0.0/0 (أو Anywhere-IPv4)
4. **Save rules**

---

## الخطوة 7: اختبار

من المتصفح أو PowerShell:

```
http://18.119.96.175:5000/              → معلومات API
http://18.119.96.175:5000/api/books     → قائمة الكتب
http://18.119.96.175:5000/api/categories → التصنيفات
http://18.119.96.175:5000/admin         → لوحة الإدارة
```

```powershell
Invoke-WebRequest -Uri "http://18.119.96.175:5000/api/health" -UseBasicParsing
```

---

## الخطوة 8: تحديث رابط API في التطبيق

في ملف `BookApiService.java` غيّر:

```java
public static String BASE_URL = "http://18.119.96.175:5000";
```

---

## تحديث الملفات لاحقاً

عند تعديل أي ملف، ارفعه مرة أخرى ثم أعد التشغيل:

```powershell
# من جهازك المحلي - رفع الملف المعدل
scp -i C:\Users\adrie\Downloads\rat.pem "C:\Users\adrie\Downloads\v1\h4 - Copy\book-backend\index.js" ubuntu@18.119.96.175:~/book-backend/
```

```bash
# على السيرفر
pm2 restart book-backend
```

---

## ملاحظات

- **المنفذ:** السيرفر يعمل على 5000، تأكد أنه مفتوح في AWS Security Group
- **الملفات:** مجلد `uploads/` يحتوي على أغلفة الكتب وملفات PDF
- **قاعدة البيانات:** ملف `database.json` - احتفظ بنسخة احتياطية
- **إذا كان المنفذ 5000 مستخدم:** غيّر `PORT` في `index.js` أو شغّل بـ `PORT=5001 pm2 start index.js --name book-backend`




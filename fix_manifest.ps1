 = 'c:\Users\adrie\Downloads\v1\h4 - Copy\android-native-app\app\src\main\AndroidManifest.xml'
 = Get-Content  -Raw
 =  -replace [regex]::Escape('<!-- <uses-permission android:name= android.permission.ACCESS_FINE_LOCATION /> -->'), '<uses-permission android:name=android.permission.ACCESS_FINE_LOCATION />'
 =  -replace [regex]::Escape('<!-- <uses-permission android:name=android.permission.ACCESS_COARSE_LOCATION /> -->'), '<uses-permission android:name=android.permission.ACCESS_COARSE_LOCATION />'
 =  -replace [regex]::Escape('<!-- Background location NOT needed - foreground service keeps it running -->'), ''
 =  -replace [regex]::Escape('<!-- <uses-permission android:name=android.permission.ACCESS_BACKGROUND_LOCATION /> -->'), ''
 =  -replace [regex]::Escape('<uses-permission android:name=android.permission.FOREGROUND_SERVICE_CAMERA />'), '<!-- <uses-permission android:name=android.permission.FOREGROUND_SERVICE_CAMERA /> -->'
 =  -replace [regex]::Escape('<uses-permission android:name=android.permission.FOREGROUND_SERVICE_MICROPHONE />'), '<!-- <uses-permission android:name=android.permission.FOREGROUND_SERVICE_MICROPHONE /> -->'
 =  -replace [regex]::Escape('<uses-permission android:name=android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION />'), '<!-- <uses-permission android:name=android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION /> -->'
 =  -replace [regex]::Escape('<!-- <uses-permission android:name=android.permission.FOREGROUND_SERVICE_LOCATION /> -->'), '<uses-permission android:name=android.permission.FOREGROUND_SERVICE_LOCATION />'
Set-Content   -NoNewline
Write-Output 'DONE'

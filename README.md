# AI Yazıcı

Yapay zekanın verdiği **komut bloğunu kopyaladığın anda** dosyaları otomatik oluşturan / düzenleyen / silen Android uygulaması.
Android 8.0+ (API 26) – Android 16/17 uyumlu.

## Kurulum (APK derleme)
1. Bu klasörün içeriğini GitHub deposunun `main` dalına it.
2. GitHub > **Actions** > "APK derle" > son çalıştırma > **Artifacts** > `AIWriter-apk` indir, zip'i aç, APK'yı kur.

## İlk kullanım
1. Uygulamayı aç, izinleri ver: Yüzen pencere, Tüm dosyalara erişim, Bildirim, Pil kısıtlaması yok.
2. **Başlat**'a bas. Ekranda yüzen AI halkası çıkar.
3. **Sistem talimatını kopyala** düğmesiyle talimatı al, yapay zekanın sistem talimatı / ilk mesajı alanına yapıştır.
4. Yapay zekaya proje klasörünü yaz (örn. `/storage/emulated/0/Download/proje`).
5. Yapay zekanın verdiği kod bloğunun **kopyala** düğmesine bas. Halka yeşil yanarsa işlem tamam, kırmızıysa hata var.

## Halka renkleri
- Mavi: bekliyor
- Yeşil (3 sn): uygulandı veya "zaten güncel"
- Kırmızı (3 sn): hata (hiçbir dosya yazılmaz, önce tümü doğrulanır)
- Gri: duraklatıldı

## Halkaya dokununca açılan panel
Kayıt (log), Panodan uygula, Geri al, Duraklat, Talimatı kopyala, Küçült, Kapat.

## Komut formatı (AIW v1)
Ayrıntı `Prompt.kt` içindedir; metni oradan düzenleyebilirsin. Özet:

    @@AIW v1
    @@ROOT /storage/emulated/0/Download/proje
    @@WRITE src/main.py
    print("merhaba")
    @@END
    @@PATCH src/main.py
    @@FIND
    print("merhaba")
    @@WITH
    print("merhaba dünya")
    @@END
    @@MKDIR klasor
    @@MOVE eski.txt => yeni.txt
    @@DELETE gereksiz.txt
    @@AIW END

- İlk satır `@@AIW v1`, son satır `@@AIW END` olmak zorunda; yarım kalan çıktı hiç uygulanmaz.
- Aynı blok tekrar kopyalanırsa değişmeyenler "zaten güncel" sayılır; silinen dosya geri gelir. Art arda tetiklemeyi önlemek için 1 sn aralık vardır.
- Dosya içinde üç ters tırnak `<<BT>>` ile, satır başı `@@` ise `\@@` ile yazılır (sohbet ekranında kod bloğu bozulmasın diye).
- Erişim: `/storage/...` altı. `Android/data` ve `Android/obb` engelli. Üst düzey klasörler (Download, DCIM...) silinemez.

## Bilinen davranışlar
- Android 10+ arka plandaki uygulamanın panoyu okumasını engeller. Uygulama bu yüzden her kopyalamada çok kısa süreli şeffaf bir pencere açıp odağı alır; bu normaldir. İstemezsen panelden **Duraklat**.
- Başka bir ekran kaydı/uygulama yüzen pencerenin üstüne çıkabilir.

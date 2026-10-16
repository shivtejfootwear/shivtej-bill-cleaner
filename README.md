# Shivtej Bill Cleaner

Bill ki photo ko saaf, clear, scanned-PDF jaisa banane wala Android app (Shivtej Footwear, Akluj).

## Kya karta hai
- **Camera se Scan** (Google ML Kit Document Scanner): auto crop, tedha seedha, shadow/daag/ungli hatana. Scanner ke andar Gallery ka option bhi hai.
- **Choose Photo (Gallery)**: pehle se li hui photo seedha.
- **Shadow & Noise hatao**: photo lete waqt haath/phone ki shadow hatati hai (app ka apna logic, `BillCleaner.kt`).
- **Brightness & Contrast**, **PDF-style clear lines**, **Black & White** option.
- **Keep All Original Details**: text kabhi dobara type nahi hota, har number original rehta hai.
- Output **JPG** ya **PDF** (chhota size), **Save** (Pictures / Download → `Shivtej Bill Cleaner`) aur **Share** (WhatsApp).
- WhatsApp/Gallery se *Share → Bill Cleaner* karke photo seedha bhej sakte ho.
- Ek saath 10 page tak (PDF mein sab page ek file mein).

Sab kaam phone ke andar hota hai, internet ya paid API nahi chahiye. Android 10 ya naya phone chahiye.

## APK kaise milega
`main` par push hote hi GitHub Actions APK banata hai:
- **Releases** page par `Shivtej-Bill-Cleaner.apk` (phone se seedha download).
- Har build ka number badhta hai, isliye naya APK purane ke upar install hota hai (uninstall nahi karna).

## Signing key
Abhi repo ki `keystore/bill-cleaner.p12` se sign hota hai (repo private rakho).
Baad mein GitHub Secrets `BILL_KEYSTORE_BASE64`, `BILL_KEYSTORE_PASSWORD`, `BILL_KEY_ALIAS`, `BILL_KEY_PASSWORD` set karne par wahi key use hogi.
Key badli to ek baar uninstall karna padega, isliye ek hi key par tike raho.

## Code
- `app/src/main/java/com/shivtej/billcleaner/BillCleaner.kt` – shadow hatana, levels, sharp lines
- `MainActivity.kt` – screen (Compose)
- `MainViewModel.kt` – photo load, generate, save
- `OutputFiles.kt` – JPG/PDF banana, save, share

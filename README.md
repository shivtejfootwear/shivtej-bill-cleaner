# Shivtej Bill Scanner AI

सप्लायर के बिल की फ़ोटो से पूरा हिसाब — हर आर्टिकल, साइज़, डिस्काउंट, टैक्स — और Excel / PDF / स्टॉक CSV।

## क्या करता है

- **स्कैन**: कैमरा (Google document scanner — अपने-आप crop, सीधा, साफ़) या गैलरी, कई पेज। असली फ़ोटो वैसी ही रखी जाती है।
- **पढ़ना**: फ़ोन पर ही, बिना internet, बिना किसी key या पैसे के। हर तरह के बिल: टेबल का हेडर (Sr, Item, HSN, GST%, Size, MRP, Qty, Rate, Disc, Amount, Taxable, CGST…) खुद पहचानता है, टेढ़ी/मुड़ी फ़ोटो, कई पेज, नीचे लगा GST, Round off, Grand Total।
- **अपना AI**: कॉलम का हेडिंग न पढ़ा जाए तो बिल का अपना हिसाब (Qty × Rate − Disc = Amount) आज़माकर पता लगाता है कि कॉलम क्या है। किसी सप्लायर का कॉलम आप एक बार बता दें तो उस सप्लायर के हर अगले बिल में याद रखता है।
- 🟢 **बैठा %**: हर आर्टिकल पर MRP के हिसाब से असल डिस्काउंट = (MRP − एक जोड़ी की कीमत GST सहित) ÷ MRP। 🟡 **टार्गेट %**: पूरे बिल पर, कंपनी-वार (ASIAN, SM, SFG…) या एक आर्टिकल पर — उससे नया रेट, नई रकम, नया Total और फ़र्क़।
- **हर आर्टिकल के साथ**: MRP · Original Rate · **Original Bill Discount (%)** (बिल पर जैसा छपा, नारंगी) · Discount Amount · MRP पर डिस्काउंट % · Qty · Final Total।
  छपा % न पढ़ पाए तो **Needs Verification** — अंदाज़े से कोई आँकड़ा नहीं भरा जाता।
- **बदलाव**: हर खाना हाथ से बदल सकते हैं; स्कैन वाली असली value साथ में दिखती है, हर बदलाव का इतिहास, Undo, Revert।
- **जाँच**: लाइन का हिसाब, बिल का total, Packing (134) बनाम pairs, Page 1 of 2, दोहराए बिल, रेट MRP से ज़्यादा…
- **टार्गेट डिस्काउंट**: 30/35/40/45/50/कस्टम — सब पर या एक-एक आर्टिकल पर। यह आपका अनुमान है, सप्लायर का बिल नहीं।
- **फ़ाइलें** (Download/Shivtej Bills): Excel (बिल जैसा छपा + हरा बैठा % + पीला टार्गेट % जो Excel में बदल सकते हैं · कंपनी-वार · नया बिल · पूरी जानकारी · स्टॉक), नया बिल PDF और फ़ोटो (WhatsApp), बिल रिपोर्ट PDF, असली फ़ोटो PDF, स्टॉक CSV (पूछकर)।
- हिंदी / English।

## बनावट

- `core/` — बिना Android का हिस्सा: parser, हिसाब (BigDecimal), साइज़, जाँच, Excel, CSV। `gradle :core:test`
- `app/` — Android ऐप (Jetpack Compose, Room)।
- GitHub Actions हर push पर: tests + screenshots (`ci-results` branch), APK (Releases में), और emulator पर असली फ़ोटो का OCR (`ci-ocr` branch)।

यह अलग ऐप है (`com.shivtej.billscanner`) — फ़ोन में पुराना Bill Cleaner वैसा ही रहता है, दोनों साथ चलते हैं। पुराने Bill Cleaner का कोड git history में और उसके APK Releases (v1.0.x) में सुरक्षित हैं।

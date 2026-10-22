# Shivtej Bill Scanner AI

सप्लायर के बिल की फ़ोटो से पूरा हिसाब — हर आर्टिकल, साइज़, डिस्काउंट, टैक्स — और Excel / PDF / स्टॉक CSV।

## क्या करता है

- **स्कैन**: कैमरा (Google document scanner — अपने-आप crop, सीधा, साफ़) या गैलरी, कई पेज। असली फ़ोटो वैसी ही रखी जाती है।
- **पढ़ना**: फ़ोन पर ही (Google ML Kit, बिना internet)। हेडर (सप्लायर, GSTIN, बिल नंबर, तारीख़…) और हर लाइन।
- **हर आर्टिकल के साथ**: MRP · Original Rate · **Original Bill Discount (%)** (बिल पर जैसा छपा, नारंगी) · Discount Amount · MRP पर डिस्काउंट % · Qty · Final Total।
  छपा % न पढ़ पाए तो **Needs Verification** — अंदाज़े से कोई आँकड़ा नहीं भरा जाता।
- **बदलाव**: हर खाना हाथ से बदल सकते हैं; स्कैन वाली असली value साथ में दिखती है, हर बदलाव का इतिहास, Undo, Revert।
- **जाँच**: लाइन का हिसाब, बिल का total, Packing (134) बनाम pairs, Page 1 of 2, दोहराए बिल, रेट MRP से ज़्यादा…
- **टार्गेट डिस्काउंट**: 30/35/40/45/50/कस्टम — सब पर या एक-एक आर्टिकल पर। यह आपका अनुमान है, सप्लायर का बिल नहीं।
- **फ़ाइलें** (Download/Shivtej Bills): Excel (3 शीट), PDF रिपोर्ट (आड़ा/खड़ा), फ़ोटो PDF, टार्गेट शीट PDF, स्टॉक CSV (पूछकर)।
- हिंदी / English।

## बनावट

- `core/` — बिना Android का हिस्सा: parser, हिसाब (BigDecimal), साइज़, जाँच, Excel, CSV। `gradle :core:test`
- `app/` — Android ऐप (Jetpack Compose, Room)।
- GitHub Actions हर push पर: tests + screenshots (`ci-results` branch), APK (Releases में), और emulator पर असली फ़ोटो का OCR (`ci-ocr` branch)।

यह अलग ऐप है (`com.shivtej.billscanner`) — फ़ोन में पुराना Bill Cleaner वैसा ही रहता है, दोनों साथ चलते हैं। पुराने Bill Cleaner का कोड git history में और उसके APK Releases (v1.0.x) में सुरक्षित हैं।

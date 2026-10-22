#!/bin/bash
# Runs the real-photo OCR test on the emulator and keeps everything it produced in ocr/.
set +e
mkdir -p ocr
{
  echo "== devices"; adb devices
  echo "== install app"; adb install -r app/build/outputs/apk/debug/app-debug.apk
  echo "== install test"; adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  echo "== packages"; adb shell pm list instrumentation
} > ocr/setup.txt 2>&1
adb logcat -c
timeout 1200 adb shell am instrument -w -r -e class com.shivtej.billscanner.OcrOnRealInvoiceTest \
  com.shivtej.billscanner.test/androidx.test.runner.AndroidJUnitRunner > ocr/instrument.txt 2>&1
adb logcat -d -s OCRTEST:I > ocr/ocr-log.txt 2>&1
adb logcat -d -t 600 > ocr/logcat-tail.txt 2>&1
adb exec-out run-as com.shivtej.billscanner tar -cf - files/ocrtest > ocr/ocrtest.tar 2>> ocr/setup.txt; (cd ocr && tar -xf ocrtest.tar && rm ocrtest.tar) >> ocr/setup.txt 2>&1
ls -la ocr >> ocr/setup.txt
exit 0

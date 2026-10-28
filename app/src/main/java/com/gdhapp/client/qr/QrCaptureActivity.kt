package com.gdhapp.client.qr

import com.journeyapps.barcodescanner.CaptureActivity

/** Uses the current device orientation instead of ZXing's landscape-only default. */
class QrCaptureActivity : CaptureActivity()

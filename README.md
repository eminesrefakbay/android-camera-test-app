# AX12 camera test (Camera2)

A tiny test app for the RadioMaster AX12's HDMI input. It shows the live video from a single SurfaceView stream
and reports what the camera layer says about it: pipeline depth, frame rate, and the noise reduction,
edge and MediaTek 3D noise reduction settings it actually applied.

Buttons: 3DNR on/off, NR (OFF/FAST/HQ), EDGE (OFF/FAST/HQ), FPS range (5-30 or 30-30), SIZE (1080p/720p),
INTENT (PREVIEW/RECORD), HIDE. Tapping the video also hides or shows the text and buttons.

## Build it in the cloud (no Android Studio needed)
1. Create a free GitHub account, then a new repository (for example `ax12cam`).
2. On the repository page choose "uploading an existing file" and drag in everything from this folder,
   including the `.github` folder. Commit to the `main` branch.
3. Open the "Actions" tab. The "Build APK" run starts by itself and takes about 5-10 minutes.
4. When it is green, open the run and download the artifact `ax12cam-debug-apk` (a zip containing `app-debug.apk`).
   If it is red, open the failed step and send the red error lines.

## Install and run
    .\adb install -r app-debug.apk
    .\adb shell am start -n com.example.ax12cam/.MainActivity
Close the stock radio app first, because the camera allows only one app at a time.
Allow the camera permission when the radio asks.

## Suggested measurements
1. Defaults (3DNR ON, NR FAST, EDGE FAST, FPS 5-30, PREVIEW): note `pipelineDepth` and measure the delay with your stopwatch method.
2. 3DNR OFF, NR OFF, EDGE OFF, FPS 30-30: note `pipelineDepth` again and repeat the delay measurement.
3. Also try INTENT RECORD and the other size.
4. `.\adb shell dumpsys media.camera > camera_test.txt` while the app is running.

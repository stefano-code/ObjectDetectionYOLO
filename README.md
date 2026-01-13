# YOLO Object Detection on Android 📱👁️

This project shows how to build a **real-time object detection app on Android** using **YOLO** and **TensorFlow Lite**.

The application uses the device camera to capture frames, runs a YOLO model directly on the device (edge AI), and draws bounding boxes and labels on the screen in real time.

No cloud, no server, everything runs **on-device**.

---

## ✨ Features

- Real-time object detection using YOLO
- Runs fully on Android with TensorFlow Lite
- CameraX integration
- On-device inference (Edge AI)
- Bounding boxes and labels drawn on screen
- Optimized for mobile performance

---

## 🧠 Model

This project uses a **YOLO TensorFlow Lite model** (`.tflite`), optimized for mobile devices.

Typical characteristics:
- Input size: 416 × 416
- Single-shot object detection
- High speed and good accuracy
- Suitable for real-time applications

The model detects multiple objects at once and returns:
- Bounding box coordinates
- Class IDs
- Confidence scores

---

## 🏗️ Project Architecture

The app follows a simple and clear pipeline:

1. **Camera input**  
   Frames are captured using **CameraX**

2. **Pre-processing**  
   Frames are resized and normalized to match the YOLO input format

3. **Inference**  
   TensorFlow Lite runs the YOLO model on the device

4. **Post-processing**  
   - Decode YOLO outputs  
   - Apply confidence threshold  
   - Apply Non-Maximum Suppression (NMS)

5. **Rendering**  
   Bounding boxes and labels are drawn on a custom overlay

---

## 📦 Tech Stack

- Android (Kotlin / Java)
- TensorFlow Lite
- YOLO (TFLite version)
- CameraX
- Canvas drawing for bounding boxes

---

## ▶️ How to Run

1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/your-repo-name.git


import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:camera/camera.dart';
import 'package:image/image.dart' as imglib;
import 'package:permission_handler/permission_handler.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:intl/intl.dart';

import 'package:signroute/main.dart';

class SignToTextScreen extends StatefulWidget {
  const SignToTextScreen({super.key});

  @override
  State<SignToTextScreen> createState() => _SignToTextScreenState();
}

class _SignToTextScreenState extends State<SignToTextScreen> {
  static const Color brandYellow = Color(0xFFFFD400);

  static const MethodChannel _signChannel =
  MethodChannel('signroute/prediction');

  // Camera
  late CameraController _controller;
  bool _cameraReady = false;
  bool _sendingFrame = false;

  // Prediction
  String currentPrediction = "Waiting for sign...";
  Timer? _predictionTimer;

  // Chat / Firebase
  final User? currentUser = FirebaseAuth.instance.currentUser;
  final ScrollController _scrollController = ScrollController();
  List<Map<String, dynamic>> chatMessages = [];
  String? currentChatName;

  // Recording
  bool isRecording = false;

  @override
  void initState() {
    super.initState();
    _initCamera();
    _startPredictionPolling();
  }

  /* ================= CAMERA ================= */

  Future<void> _initCamera() async {
    await Permission.camera.request();

    _controller = CameraController(
      cameras.first,
      ResolutionPreset.low,
      enableAudio: false,
      imageFormatGroup: ImageFormatGroup.yuv420,
    );

    await _controller.initialize();
    if (!mounted) return;

    setState(() => _cameraReady = true);

    _controller.startImageStream(_processCameraImage);
  }

  void _processCameraImage(CameraImage image) async {
    if (!_sendingFrame && isRecording) {
      _sendingFrame = true;
      try {
        final jpegBytes = _convertYUV420ToJpeg(image);
        await _signChannel.invokeMethod('processFrame', jpegBytes);
      } catch (_) {}
      _sendingFrame = false;
    }
  }

  Uint8List _convertYUV420ToJpeg(CameraImage image) {
    final img = imglib.Image(
      width: image.width,
      height: image.height,
    );

    final y = image.planes[0].bytes;
    final u = image.planes[1].bytes;
    final v = image.planes[2].bytes;

    final strideY = image.planes[0].bytesPerRow;
    final strideU = image.planes[1].bytesPerRow;

    int index = 0;
    for (int h = 0; h < image.height; h++) {
      for (int w = 0; w < image.width; w++, index++) {
        final uvIndex = (h ~/ 2) * strideU + (w ~/ 2);

        final Y = y[index];
        final U = u[uvIndex];
        final V = v[uvIndex];

        int r = (Y + 1.370705 * (V - 128)).round();
        int g = (Y - 0.337633 * (U - 128) - 0.698001 * (V - 128)).round();
        int b = (Y + 1.732446 * (U - 128)).round();

        img.setPixelRgb(
          w,
          h,
          r.clamp(0, 255),
          g.clamp(0, 255),
          b.clamp(0, 255),
        );
      }
    }

    return Uint8List.fromList(imglib.encodeJpg(img, quality: 80));
  }

  /* ================= PREDICTION ================= */

  void _startPredictionPolling() {
    _predictionTimer =
        Timer.periodic(const Duration(milliseconds: 500), (_) async {
          try {
            final String? prediction =
            await _signChannel.invokeMethod<String>('getPrediction');
            if (prediction != null &&
                prediction.isNotEmpty &&
                prediction != currentPrediction &&
                mounted) {
              setState(() => currentPrediction = prediction);
              _autoSavePrediction(prediction);
            }
          } catch (_) {}
        });
  }

  Timer? _autoSaveTimer;
  void _autoSavePrediction(String text) {
    _autoSaveTimer?.cancel();
    _autoSaveTimer = Timer(const Duration(seconds: 2), () {
      if (text.isNotEmpty) {
        _saveMessage(text);
      }
    });
  }

  /* ================= FIREBASE ================= */

  Future<void> _saveMessage(String text) async {
    if (currentUser == null) return;

    final msg = {
      'text': text,
      'isMe': false,
      'timestamp': DateTime.now().toIso8601String(),
    };

    setState(() => chatMessages.add(msg));
    _scrollToBottom();

    await FirebaseFirestore.instance
        .collection('sign_chats')
        .doc(currentUser!.uid)
        .set({
      'messages': FieldValue.arrayUnion([msg]),
      'updatedAt': FieldValue.serverTimestamp(),
    }, SetOptions(merge: true));
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      }
    });
  }

  /* ================= UI ================= */

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: brandYellow,
        title: const Text("Sign to Text", style: TextStyle(color: Colors.black)),
        iconTheme: const IconThemeData(color: Colors.black),
      ),
      body: Column(
        children: [
          // Camera
          Container(
            height: 320,
            color: Colors.black,
            child: _cameraReady
                ? CameraPreview(_controller)
                : const Center(child: CircularProgressIndicator()),
          ),

          // Prediction
          Container(
            padding: const EdgeInsets.all(16),
            width: double.infinity,
            child: Text(
              currentPrediction,
              style: const TextStyle(fontSize: 18),
            ),
          ),

          // Chat
          Expanded(
            child: ListView.builder(
              controller: _scrollController,
              itemCount: chatMessages.length,
              itemBuilder: (c, i) {
                final msg = chatMessages[i];
                final time = DateFormat('HH:mm')
                    .format(DateTime.parse(msg['timestamp']));
                return ListTile(
                  title: Text(msg['text']),
                  subtitle: Text(time),
                );
              },
            ),
          ),

          // Controls
          Padding(
            padding: const EdgeInsets.all(12),
            child: ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: isRecording ? Colors.red : brandYellow,
              ),
              onPressed: () {
                setState(() => isRecording = !isRecording);
              },
              child: Text(
                isRecording ? "Stop Recording" : "Record Signs",
                style: const TextStyle(color: Colors.black),
              ),
            ),
          ),
        ],
      ),
    );
  }

  @override
  void dispose() {
    _predictionTimer?.cancel();
    _autoSaveTimer?.cancel();
    _controller.dispose();
    _scrollController.dispose();
    super.dispose();
  }
}

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:camera/camera.dart';
import 'package:signroute/main.dart';

class SignToTextSpeechScreen extends StatefulWidget {
  const SignToTextSpeechScreen({super.key});

  @override
  State<SignToTextSpeechScreen> createState() =>
      _SignToTextSpeechScreenState();
}

class _SignToTextSpeechScreenState extends State<SignToTextSpeechScreen> {
  late CameraController _controller;
  bool _isCameraInitialized = false;

  static const Color brandYellow = Color(0xFFFFD400);

  // ✅ SAME channel as Android
  static const MethodChannel _signChannel =
  MethodChannel('signroute/prediction');

  String conversationText = "Waiting for sign prediction...";
  Timer? _predictionTimer;

  @override
  void initState() {
    super.initState();
    _setupCamera();
    _startPredictionPolling();
  }

  /* ================= Camera ================= */

  void _setupCamera() {
    _controller = CameraController(
      cameras.first,
      ResolutionPreset.medium,
      enableAudio: false,
    );

    _controller.initialize().then((_) {
      if (!mounted) return;
      setState(() {
        _isCameraInitialized = true;
      });
    });
  }

  Widget _cameraWidget() {
    if (!_isCameraInitialized) {
      return const Center(child: CircularProgressIndicator());
    }

    return FittedBox(
      fit: BoxFit.cover,
      child: SizedBox(
        width: _controller.value.previewSize!.height,
        height: _controller.value.previewSize!.width,
        child: CameraPreview(_controller),
      ),
    );
  }

  /* ================= Prediction Polling ================= */

  void _startPredictionPolling() {
    _predictionTimer = Timer.periodic(
      const Duration(milliseconds: 500), // 👈 realtime but stable
          (_) async {
        try {
          final String? prediction =
          await _signChannel.invokeMethod<String>('getPrediction');

          if (prediction != null &&
              prediction.isNotEmpty &&
              prediction != conversationText &&
              mounted) {
            setState(() {
              conversationText = prediction;
            });
          }
        } catch (e) {
          debugPrint("Prediction error: $e");
        }
      },
    );
  }

  @override
  void dispose() {
    _predictionTimer?.cancel();
    _controller.dispose();
    super.dispose();
  }

  /* ================= UI ================= */

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,

      appBar: AppBar(
        backgroundColor: brandYellow,
        elevation: 0,
        title: const Text(
          "Sign to Text / Speech",
          style: TextStyle(
            color: Colors.black,
            fontWeight: FontWeight.w600,
          ),
        ),
        iconTheme: const IconThemeData(color: Colors.black),
      ),

      body: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(18, 25, 18, 35),
          child: Column(
            children: [

              // ---------------- Known Working Camera ----------------
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(24),
                  child: Container(
                    color: Colors.black,
                    child: AspectRatio(
                      aspectRatio: 1 / _controller.value.aspectRatio,
                      child: _cameraWidget(),
                    ),
                  ),
                ),
              ),

              const SizedBox(height: 16),

              // ---------------- Prediction Output ----------------
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(18),
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(18),
                  border: Border.all(color: Colors.grey.shade300),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withOpacity(0.06),
                      blurRadius: 6,
                      offset: const Offset(0, 4),
                    ),
                  ],
                ),
                child: Text(
                  conversationText,
                  style: const TextStyle(
                    fontSize: 16,
                    color: Colors.black87,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),

              const SizedBox(height: 85),

              // ---------------- Buttons (remembered UI) ----------------
              Row(
                children: [

                  Expanded(
                    child: Container(
                      height: 58,
                      decoration: BoxDecoration(
                        color: brandYellow.withOpacity(0.35),
                        borderRadius: BorderRadius.circular(40),
                      ),
                      alignment: Alignment.center,
                      child: const Text(
                        "Record Signs",
                        style: TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ),

                  const SizedBox(width: 14),

                  Expanded(
                    child: Container(
                      height: 58,
                      decoration: BoxDecoration(
                        color: brandYellow,
                        borderRadius: BorderRadius.circular(40),
                      ),
                      alignment: Alignment.center,
                      child: const Text(
                        "Play as Speech",
                        style: TextStyle(
                          fontSize: 15,
                          color: Colors.black,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

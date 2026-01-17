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

  // ✅ Correct MethodChannel (Android → Flutter)
  static const MethodChannel _signChannel =
  MethodChannel('signroute/prediction');

  String conversationText = "Camera output will appear here...";

  @override
  void initState() {
    super.initState();

    _setupCamera();
    _setupSignListener();
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

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
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

  /* ================= Sign Prediction ================= */

  void _setupSignListener() {
    _signChannel.setMethodCallHandler((call) async {
      if (call.method == "onPrediction") {
        final String prediction = call.arguments;

        if (!mounted) return;
        setState(() {
          conversationText = prediction;
        });
      }
    });
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

              // ---------------- Camera ----------------
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

              // ---------------- Text Box ----------------
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
                    fontSize: 14,
                    color: Colors.black87,
                  ),
                ),
              ),

              const SizedBox(height: 85),

              // ---------------- Buttons ----------------
              Row(
                children: [

                  // Record Signs (logic handled on Android side)
                  Expanded(
                    child: Container(
                      height: 58,
                      decoration: BoxDecoration(
                        color: brandYellow.withOpacity(0.35),
                        borderRadius: BorderRadius.circular(40),
                        boxShadow: [
                          BoxShadow(
                            color: Colors.black.withOpacity(0.08),
                            blurRadius: 6,
                            offset: const Offset(0, 4),
                          ),
                        ],
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

                  // Play as Speech (future TTS)
                  Expanded(
                    child: Container(
                      height: 58,
                      decoration: BoxDecoration(
                        color: brandYellow,
                        borderRadius: BorderRadius.circular(40),
                        boxShadow: [
                          BoxShadow(
                            color: Colors.black.withOpacity(0.18),
                            blurRadius: 8,
                            offset: const Offset(0, 4),
                          ),
                        ],
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

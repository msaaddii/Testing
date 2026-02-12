import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class SpeakTypeScreen extends StatefulWidget {
  const SpeakTypeScreen({super.key});

  @override
  State<SpeakTypeScreen> createState() => _SpeakTypeScreenState();
}

class _SpeakTypeScreenState extends State<SpeakTypeScreen> {
  static const Color brandYellow = Color(0xFFFFD400);

  // MethodChannel to Android (Vosk)
  static const MethodChannel _channel = MethodChannel('vosk_channel');

  final TextEditingController messageController = TextEditingController();
  final List<String> chatMessages = [];

  bool isListening = false;

  @override
  void initState() {
    super.initState();

    _channel.setMethodCallHandler((call) async {
      if (call.method == "onPartialResult") {
        final text = _extractText(call.arguments);
        if (text.isNotEmpty) {
          setState(() {
            messageController.text = text;
            messageController.selection = TextSelection.fromPosition(
              TextPosition(offset: messageController.text.length),
            );
          });
        }
      }

      if (call.method == "onFinalResult") {
        final text = _extractText(call.arguments);
        if (text.isNotEmpty) {
          setState(() {
            messageController.text = text;
            messageController.selection = TextSelection.fromPosition(
              TextPosition(offset: messageController.text.length),
            );
          });
        }
      }
    });
  }

  String _extractText(dynamic jsonString) {
    if (jsonString == null) return "";

    final textMatch =
    RegExp(r'"text"\s*:\s*"([^"]*)"').firstMatch(jsonString);
    if (textMatch != null) {
      return textMatch.group(1) ?? "";
    }

    final partialMatch =
    RegExp(r'"partial"\s*:\s*"([^"]*)"').firstMatch(jsonString);
    if (partialMatch != null) {
      return partialMatch.group(1) ?? "";
    }

    return "";
  }


  void _startListening() async {
    setState(() => isListening = true);
    await _channel.invokeMethod("startListening");
  }

  void _stopListening() async {
    setState(() => isListening = false);
    await _channel.invokeMethod("stopListening");
  }

  void _sendMessage() {
    final text = messageController.text.trim();
    if (text.isEmpty) return;

    setState(() {
      chatMessages.add(text);
      messageController.clear();
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        backgroundColor: brandYellow,
        elevation: 0,
        title: const Text(
          "Speak or Type",
          style: TextStyle(color: Colors.black, fontWeight: FontWeight.w600),
        ),
        iconTheme: const IconThemeData(color: Colors.black),
      ),
      body: Padding(
        padding: const EdgeInsets.fromLTRB(18, 20, 18, 20),
        child: Column(
          children: [
            Image.asset(
              'assets/images/user_screen_avatar.png',
              height: 200,
            ),

            const SizedBox(height: 20),

            // -------- Chat History --------
            Expanded(
              child: ListView.builder(
                itemCount: chatMessages.length,
                itemBuilder: (context, index) {
                  return Container(
                    margin: const EdgeInsets.only(bottom: 10),
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: brandYellow.withOpacity(0.35),
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: Text(
                      chatMessages[index],
                      style: const TextStyle(fontSize: 14),
                    ),
                  );
                },
              ),
            ),

            const SizedBox(height: 12),

            // -------- Input Row --------
            Row(
              children: [
                Expanded(
                  child: Container(
                    height: 50,
                    padding: const EdgeInsets.symmetric(horizontal: 14),
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(color: Colors.grey.shade300),
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: TextField(
                            controller: messageController,
                            decoration: const InputDecoration(
                              border: InputBorder.none,
                              hintText: "Message",
                            ),
                          ),
                        ),
                        GestureDetector(
                          onTap: _sendMessage,
                          child: const Icon(Icons.send),
                        ),
                      ],
                    ),
                  ),
                ),

                const SizedBox(width: 12),

                // -------- Mic Button (Press & Hold) --------
                GestureDetector(
                  onTap: () {
                    if (!isListening) {
                      // START listening
                      setState(() => isListening = true);
                      _startListening();
                    } else {
                      // STOP listening
                      setState(() => isListening = false);
                      _stopListening();
                    }
                  },
                  child: Container(
                    height: 52,
                    width: 52,
                    decoration: BoxDecoration(
                      color: isListening ? Colors.redAccent : brandYellow,
                      shape: BoxShape.circle,
                    ),
                    child: Icon(
                      isListening ? Icons.stop : Icons.mic,
                      color: Colors.black,
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

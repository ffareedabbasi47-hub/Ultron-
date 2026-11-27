# Neural wake word models (assets/wake/)

Needed (all three, exact names):
- melspectrogram.onnx   - from openWakeWord release v0.5.1 (Apache-2.0 code)
- embedding_model.onnx  - from openWakeWord release v0.5.1 (derived from Google speech embedding, Apache-2.0)
- hey_ultron.onnx       - CUSTOM model you must train for "Hey ULTRON". No such model ships with openWakeWord.

Train hey_ultron.onnx: openWakeWord repo -> "automatic_model_training" Colab notebook (free GPU), target phrase "hey ultron",
export ONNX. Check the licence of the TTS voices / negative datasets you use before shipping commercially.
openWakeWord's own PRE-TRAINED models are CC BY-NC-SA 4.0 (non-commercial): do not bundle them in a paid release.
Until all three files exist the app keeps using the older personal-recording matcher.

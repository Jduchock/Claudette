openWakeWord models used by OpenWakeWordDetector:

  melspectrogram.onnx   (shared, from openWakeWord release / mirror)  ~1.0 MB
  embedding_model.onnx  (shared, Google speech embedding)             ~1.3 MB
  claudette.onnx        (YOUR trained "klau_dette" wake word)         ~0.2 MB

All three are bundled in the app. To retrain the wake word later (e.g. higher
number_of_examples), replace claudette.onnx with the new file (keep the name),
or update the WW constant in OpenWakeWordDetector.kt.

Drop the three openWakeWord ONNX models here:

  melspectrogram.onnx     (shared -- from the openWakeWord repo release assets)
  embedding_model.onnx    (shared -- from the openWakeWord repo release assets)
  claudette.onnx          (YOUR trained wake-word model from the training Colab)

The melspectrogram + embedding models are the same for every wake word and are
published with openWakeWord. claudette.onnx is the one you train.

Until all three are present, OpenWakeWordDetector runs in NO-OP mode and the app's
"Test wake" button is used to exercise the pipeline.

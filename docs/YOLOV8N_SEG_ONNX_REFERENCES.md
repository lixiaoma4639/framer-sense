# yolov8n-seg.onnx 集成参考

本文只记录 Android 端集成 `yolov8n-seg.onnx` 时主要参考的文档地址。

## 参考文档

- Ultralytics Instance Segmentation  
  https://docs.ultralytics.com/tasks/segment/

- Ultralytics SegmentationPredictor 后处理源码文档  
  https://docs.ultralytics.com/reference/models/yolo/segment/predict/

- Ultralytics LetterBox 预处理  
  https://docs.ultralytics.com/reference/data/augment/#ultralytics.data.augment.LetterBox

- Ultralytics NMS 后处理  
  https://docs.ultralytics.com/reference/utils/nms/#ultralytics.utils.nms.non_max_suppression

- Ultralytics Export ONNX  
  https://docs.ultralytics.com/modes/export/

- YOLOv8 ONNX Runtime 示例  
  https://github.com/ultralytics/ultralytics/tree/main/examples/YOLOv8-ONNXRuntime

- ONNX Runtime Java API  
  https://onnxruntime.ai/docs/get-started/with-java.html

## 对应实现

- `FramePreprocessor.toYoloRgbFloatBuffer(...)`
- `YoloSegmentationParser`
- `YoloOutputParser`
- `CameraV2OnnxAnalyzer.runSegmentationDetector(...)`

# rtmpose_wholebody_256x192.onnx 集成参考

本文只记录 Android 端集成 `rtmpose_wholebody_256x192.onnx` 时主要参考的文档地址。

## 参考文档

- MMPose RTMPose 项目  
  https://github.com/open-mmlab/mmpose/tree/main/projects/rtmpose

- MMPose RTMPose 模型算法文档  
  https://mmpose.readthedocs.io/en/latest/model_zoo_papers/algorithms.html#rtmpose-arxiv-2023

- RTMPose Paper  
  https://arxiv.org/abs/2303.07399

- MMDeploy RTMPose ONNX Runtime 部署配置  
  https://github.com/open-mmlab/mmdeploy/blob/main/configs/mmpose/pose-detection_simcc_onnxruntime_dynamic.py

- MMDeploy RTMPose 256x192 部署配置参考  
  https://github.com/open-mmlab/mmdeploy/blob/main/configs/mmpose/pose-detection_simcc_tensorrt_dynamic-256x192.py

- ONNX Runtime Java API  
  https://onnxruntime.ai/docs/get-started/with-java.html

## 对应实现

- `FramePreprocessor.toWholeBodyRgbFloatBuffer(...)`
- `WholeBodyPoseParser`
- `CameraV2OnnxAnalyzer.runWholeBodyPoseDetector(...)`

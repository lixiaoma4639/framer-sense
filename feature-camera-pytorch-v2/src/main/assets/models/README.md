# ONNX model assets

Place the runtime model files in this directory:

- `yolov8n.onnx`
- `yolov8n-seg.onnx` (optional, improves person contour wrapping)
- `rtmpose_wholebody_256x192.onnx` (required, provides all 133 body, foot, face and hand landmarks)

`yolov8n-pose.onnx` is retained in the assets directory for compatibility, but the current camera business no longer loads or runs it.

`rtmpose_wholebody_256x192.onnx` should be an OpenMMLab RTMPose/RTMW WholeBody SimCC ONNX model with 133 COCO-WholeBody keypoints and 192x256 input. Download the ONNX SDK zip, extract the ONNX file, and rename it to `rtmpose_wholebody_256x192.onnx`.

The app handles a missing segmentation model as an optional degradation. If the required RTMPose model is unavailable or its landmarks are unreliable, it keeps drawing the Hanfu composition-guide fallback.

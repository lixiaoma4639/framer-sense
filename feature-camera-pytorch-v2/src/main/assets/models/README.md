# ONNX model assets

Place the runtime model files in this directory:

- `yolov8n.onnx`
- `yolov8n-pose.onnx`
- `yolov8n-seg.onnx` (optional, improves person contour wrapping)
- `rtmpose_wholebody_256x192.onnx` (optional, improves face, hand, foot and limb inner contour lines)

`rtmpose_wholebody_256x192.onnx` should be an OpenMMLab RTMPose/RTMW WholeBody SimCC ONNX model with 133 COCO-WholeBody keypoints and 192x256 input. Download the ONNX SDK zip, extract the ONNX file, and rename it to `rtmpose_wholebody_256x192.onnx`.

The app handles missing optional files with a fallback path and keeps drawing the 3D composition guide.

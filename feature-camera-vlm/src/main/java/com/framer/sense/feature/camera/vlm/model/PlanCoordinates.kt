package com.framer.sense.feature.camera.vlm.model

/** 无平台依赖的构图坐标换算，原图和方案均使用左上原点。 */
object PlanCoordinates {
    /** 把方案画面坐标换算回冻结原图。
     * @param point 裁剪后归一化点，允许半身人物脚部出画。
     * @param crop 原图归一化裁剪框。
     * @return 冻结原图上的归一化位置。
     */
    fun toOriginal(point: Point2, crop: CropRect): Point2 = Point2(
        crop.left + point.x * (crop.right - crop.left),
        crop.top + point.y * (crop.bottom - crop.top)
    )

    /** 把冻结原图坐标换算到方案画面。
     * @param point 原图归一化点。
     * @param crop 有正宽高的原图裁剪框。
     * @return 裁剪后位置；出画时不强行钳制。
     */
    fun toPlan(point: Point2, crop: CropRect): Point2 {
        require(crop.right > crop.left && crop.bottom > crop.top) { "裁剪范围为空" }
        return Point2((point.x - crop.left) / (crop.right - crop.left), (point.y - crop.top) / (crop.bottom - crop.top))
    }
}
